package conduit

import annotation.tailrec
import collection.mutable.{ArrayBuffer, Buffer, ArrayStack, Stack, Queue}
import util.control.Exception

/**
 * Accepts input elements of type <code>I</code>, produces output elements of
 * type <code>O</code> and when finished, returns <code>R</code>.
 */
sealed trait GenPipe[-U,-I,+O,+R]
{
  @inline final def as[U1 <: U,I1 <: I]: GenPipe[U1,I1,O,R] = this;
  @inline final def asU[U1 <: U]: GenPipe[U1,I,O,R] = this;
  @inline final def asI[U1 <: U,I1 <: I]: GenPipe[U1,I1,O,R] = this;
  @inline final def asO[O1 >: O]: GenPipe[U,I,O1,R] = this;

  //final def map[R1](f: R => R1): Pipe[I,O,R1] = Pipe.map(this, f);

  //final def finalizer(fin: => Unit): Pipe[I,O,R] = Pipe.delay(this, fin);
}

/**
 * When a pipe is run, it's converted into a smaller, more specific set of
 * primitives, covered by <code>PipeCode</code>. It only allows input, output,
 * delay and producing the final result.
 */
private sealed trait PipeCore[-U,-I,+O,+R] extends GenPipe[U,I,O,R] {
  def finalizer: Pipe.Finalizer;
}

private final case class HaveOutput[-U,-I,+O,+R](output: O, next: () => GenPipe[U,I,O,R], override val finalizer: Pipe.Finalizer)
  extends PipeCore[U,I,O,R];
private final case class NeedInput[-U,-I,+O,+R](consume: I => GenPipe[U,I,O,R], noInput: U => Pipe.NoInput[U,O,R], override val finalizer: Pipe.Finalizer)
  extends PipeCore[U,I,O,R];
private final case class Done[+R](result: R)
  extends PipeCore[Any,Any,Nothing,R] {
    override def finalizer = Pipe.Finalizer.empty;
  }
private final case class Delay[-U,-I,+O,+R](next: () => GenPipe[U,I,O,R], override val finalizer: Pipe.Finalizer)
  extends PipeCore[U,I,O,R];

// We also have two more primitives that represent two core operations on
// pipes: binding and fusing. They are converted into the above ones when a
// pipe is run.

private final case class Bind[-U,-I,+O,+R,S](first: GenPipe[U,I,O,S], cont: S => GenPipe[U,I,O,R], finalizer: Pipe.Finalizer)
  extends GenPipe[U,I,O,R];
private final case class Fuse[-U,-I,X,M,+O,+R](up: GenPipe[U,I,X,M], down: GenPipe[M,X,O,R])
  extends GenPipe[U,I,O,R];
//private final case class Leftover[I,+O,+R](leftover: I, next: () => GenPipe[U,I,O,R])
//  extends GenPipe[U,I,O,R];


object Pipe {
  private object Log {
    import java.util.logging._
    // TODO
    val logger = Logger.getLogger(Pipe.getClass().getName());

    private def log(level: Level, msg: => String, ex: Throwable = null) =
      if (logger.isLoggable(level))
        logger.log(level, msg, ex);

    def warn(msg: => String, ex: Throwable = null) =
      log(Level.WARNING, msg, ex);
    def error(msg: => String, ex: Throwable = null) =
      log(Level.SEVERE, msg, ex);
  }

  class Finalizer private (private val actions: Seq[Option[Throwable] => Unit]) {
    def isEmpty = actions.isEmpty;

    protected def run(th: Option[Throwable]): Unit =
      actions.foreach(Finalizer.runQuietly(_, th));

    /**
     * If an exception occurs when running <var>body</var>, run the finalizer.
     */
    def protect[R](body: => R): R =
      if (isEmpty) body
      else
        try { body }
        catch {
          case (ex: Exception) => { run(Some(ex)); throw ex; }
          case (ex: Throwable) => {
            Log.error("Serious Error! Finalizers may fail.", ex);
            run(Some(ex));
            throw ex;
          }
        }

    def ++(that: Finalizer): Finalizer =
      new Finalizer(this.actions ++ that.actions);
  }
  object Finalizer {
    implicit val empty: Finalizer = new Finalizer(Seq.empty);

    def apply(fin: => Unit): Finalizer = new Finalizer(Seq((_) => fin));
    def apply(fin: Option[Throwable] => Unit): Finalizer = new Finalizer(Seq(fin));

    protected def runQuietly(f: Option[Throwable] => Unit, th: Option[Throwable] = None) =
      try { f(th) }
      catch {
        case (ex: Exception) => Log.warn("Exception in a finalizer", ex);
        case (ex: Throwable) => Log.error("Error in a finalizer", ex);
      }
    def run(implicit fin: Finalizer): Unit =
      fin.run(None);
  }

  type LQueue[+A] = collection.immutable.Queue[A]
  val emptyLQueue: LQueue[Nothing] = collection.immutable.Queue.empty

  type Pipe[-I,+O,+R]     = GenPipe[Any,I,O,R]
  type Sink[-I,+R]        = Pipe[I,Nothing,R]
  type Source[+O,+R]      = Pipe[Any,O,R]
  type NoInput[-U,+O,+R]  = GenPipe[U,Nothing,O,R]

  @inline
  protected val nextDone: () => Source[Nothing,Unit] = () => done;
  protected def const[A](body: => A): Any => A =
    (_) => body;

  /**
   * Returns a pipe that does nothing and returns <code>()</code>.
   */
  @inline
  val done: Source[Nothing,Unit] = done(());
  /**
   * Returns a pipe that just returns the given result.
   */
  @inline
  def done[R](result: R): Source[Nothing,R] = Done(result);
  /**
   * Returns a pipe that runs the given finalizer and then returns the given result.
   */
  @inline
  def finishF[R](result: R, fin: Finalizer): Source[Nothing,R] = {
    Finalizer.run(fin);
    Done(result);
  }

  @inline
  def delay[U,I,O,R](inner: => GenPipe[U,I,O,R])(implicit finalizer: Finalizer = Finalizer.empty): GenPipe[U,I,O,R] =
    Delay(() => inner, finalizer);

  @inline
  def request[I]: Sink[I,Option[I]] =
    requestOpt((i: Option[I]) => Done(i))(Finalizer.empty);
  @inline
  def request[U,I,O,R](cont: I => GenPipe[U,I,O,R], end: U => NoInput[U,O,R])(implicit finalizer: Finalizer): GenPipe[U,I,O,R] =
    NeedInput(cont, end, finalizer);
  def requestOpt[I,O,R](cont: Option[I] => Pipe[I,O,R])(implicit finalizer: Finalizer): Pipe[I,O,R] =
    NeedInput((i: I) => cont(Some(i)), (_) => cont(None), finalizer);
  def requestEither[U,I,O,R](cont: Either[U,I] => GenPipe[U,I,O,R])(implicit finalizer: Finalizer): GenPipe[U,I,O,R] =
    NeedInput((i: I) => cont(Right(i)), (u: U) => cont(Left(u)), finalizer);

  def requestI[U,I,O](cont: I => GenPipe[U,I,O,U])(implicit finalizer: Finalizer = Finalizer.empty): GenPipe[U,I,O,U] =
    requestE[U,I,O,U]((i: I) => cont(i), identity[U] _);
  def requestU[U,I,O](cont: I => GenPipe[U,I,O,Unit])(implicit finalizer: Finalizer = Finalizer.empty): GenPipe[U,I,O,Unit] =
    requestE[U,I,O,Unit]((i: I) => cont(i), (_) => ());
  def requestE[U,I,O,R](cont: I => GenPipe[U,I,O,R], end: U => R)(implicit finalizer: Finalizer): GenPipe[U,I,O,R] =
    request[U,I,O,R](cont, (u: U) => done(end(u)));
  def requestE[I,O,R](cont: I => Pipe[I,O,R], end: => R)(implicit finalizer: Finalizer): Pipe[I,O,R] =
    request[Any,I,O,R](cont, (_: Any) => done(end));

  @inline
  def respond[U,I,O,R](o: O, cont: => GenPipe[U,I,O,R])(implicit finalizer: Finalizer): GenPipe[U,I,O,R] =
    HaveOutput(o, () => cont, finalizer);
  @inline
  def respond[O](o: O)(implicit finalizer: Finalizer): Source[O,Unit] =
    HaveOutput(o, () => done, finalizer);

/*
  @inline
  def leftover[I,O,R](left: I, pipe: => Pipe[I,O,R]): Pipe[I,O,R] =
    Leftover(left, pipe);
  @inline
  def leftover[I](left: I): Sink[I,Unit] =
    Leftover(left, nextDone);
*/

  @inline
  def flatMap[U,I,O,R,S](pipe: GenPipe[U,I,O,S], f: S => GenPipe[U,I,O,R])(implicit finalizer: Finalizer): GenPipe[U,I,O,R] =
    Bind(pipe, f, finalizer);

  @inline
  def map[U,I,O,S,R](pipe: GenPipe[U,I,O,S], f: S => R)(implicit finalizer: Finalizer): GenPipe[U,I,O,R] =
    flatMap(pipe, (x: S) => done(f(x)));


  def andThen[U,I,O,R](first: GenPipe[U,I,O,_], cont: => GenPipe[U,I,O,R])(implicit finalizer: Finalizer): GenPipe[U,I,O,R] =
    flatMap[U,I,O,R,Any](first, const(cont));

  def untilF[U,I,O,A,B](f: A => Either[GenPipe[U,I,O,A],B], start: A)(implicit finalizer: Finalizer): GenPipe[U,I,O,B] =
    untilF[U,I,O,A,B](f, start, true);
  def untilF[U,I,O,A,B](f: A => Either[GenPipe[U,I,O,A],B], start: A, runFinalizer: Boolean)(implicit finalizer: Finalizer): GenPipe[U,I,O,B] = {
    def loop(x: A): GenPipe[U,I,O,B] =
      f(start) match {
        case Left(pipe) => flatMap(pipe, loop _);
        case Right(b)   => if (runFinalizer) Finalizer.run; done(b);
      };
    delay(loop(start));
  }
  def untilF[U,I,O](pipe: => Option[GenPipe[U,I,O,Any]])(implicit finalizer: Finalizer): GenPipe[U,I,O,Unit] =
    untilF[U,I,O](pipe, true);
  def untilF[U,I,O](pipe: => Option[GenPipe[U,I,O,Any]], runFinalizer: Boolean = true)(implicit finalizer: Finalizer): GenPipe[U,I,O,Unit] = {
    def loop(): GenPipe[U,I,O,Unit] =
      pipe match {
        case Some(pipe) => andThen(pipe, loop());
        case None       => if (runFinalizer) Finalizer.run; done;
      }
    delay { loop() }
  }

  def whileF[U,I,O](pipe: => GenPipe[U,I,O,Boolean])(implicit finalizer: Finalizer): GenPipe[U,I,O,Unit] =
    whileF[U,I,O](pipe, true);
  def whileF[U,I,O](pipe: => GenPipe[U,I,O,Boolean], runFinalizer: Boolean = true)(implicit finalizer: Finalizer): GenPipe[U,I,O,Unit] = {
    def loop(): GenPipe[U,I,O,Unit] =
      flatMap(pipe, (b: Boolean) => {
        if (b)
          loop();
        else {
          if (runFinalizer)
            Finalizer.run;
          done;
        }
      })
    delay { loop() }
  }


  @inline
  def blockInput[U,O,R](upResult: U, p: NoInput[U,O,R]): Source[O,R] =
    pipe(done(upResult), p);
  @inline
  def blockInput[O,R](p: NoInput[Unit,O,R]): Source[O,R] =
    pipe(done, p);

  @inline
  def discardOutput[I,R](p: Pipe[I,Any,Unit]): Sink[I,Unit] =
    pipe(p, discardOutput);
  val discardOutput: Pipe[Any,Nothing,Unit] =
    requestU[Any,Any,Nothing]((_) => discardOutput);
 

  /**
   * The pipes returned by <code>f</code> should not request any input. If they do,
   * they're terminated.
   */
  @inline
  def unfold[I,O](f: I => NoInput[Unit,O,Any])(implicit finalizer: Finalizer): Pipe[I,O,Unit] =
    requestU[Any,I,O](i => andThen(blockInput(f(i)), unfold(f)));
  // TODO: unfold for generic U

  def repeat[O](produce: => O)(implicit finalizer: Finalizer): Source[O,Nothing] = {
    def loop(): Source[O,Nothing]
      = respond(produce, loop());
    delay { loop() }
  }



  @inline
  def pipe[U,I,X,M,O,R](i: GenPipe[U,I,X,M], o: GenPipe[M,X,O,R]): GenPipe[U,I,O,R] =
    Fuse(i, o);


  def idP[A]: Pipe[A,A,Unit] = {
    implicit val fin = Finalizer.empty;
    requestU(x => respond(x, idP));
  }

  def mapP[I,O](f: I => O)(implicit finalizer: Finalizer): Pipe[I,O,Unit] =
    requestU(x => respond(f(x), mapP(f)));


  sealed trait Leftover[+I,+R] { val result: R; }
  final case class HasLeft[+I,+R](left: I, override val result: R)
    extends Leftover[I,R];
  final case class NoLeft[+R](override val result: R)
    extends Leftover[Nothing,R];



  def runPipe[R](pipe: GenPipe[Unit,Nothing,Nothing,R]): R = {
    import Finalizer._
    @tailrec
    def step[R](pipe: GenPipe[Unit,Nothing,Nothing,R]): R = {
      stepPipe[Unit,Nothing,Nothing,R](pipe) match {
        case Done(r)                  => r;
        case HaveOutput(o, _, _)      => o; // Never occurs - o is Nothing so it can be typed to anything.
        case NeedInput(_, end, fin)   => step(fin.protect({ end(()) }));
        case Delay(next, fin)         => step(fin.protect({ next() }));
      }
    }
    step(pipe);
  }


  private def stepPipe[U,I,O,R](pipe: GenPipe[U,I,O,R]): PipeCore[U,I,O,R] =
    pipe match {
      case p@Done(_)              => p;
      case p@NeedInput(_,_,_)     => p;
      case p@HaveOutput(o,_,_)    => p;
      case p@Delay(_,_)           => p;
      case Fuse(up, down)         => stepFuse(up, down);
      case Bind(next, cont, fin)  => stepBind(next, cont, fin);
    }

  private def stepBind[U,I,O,R,S](pipe: GenPipe[U,I,O,S], cont: S => GenPipe[U,I,O,R], fin: Finalizer): PipeCore[U,I,O,R] =
    stepPipe(pipe) match {
      case Done(s)                    => Delay(() => cont(s), fin)
      case NeedInput(cons, end, finI) => NeedInput(i  => stepBind(cons(i), cont, fin),
                                                   u  => stepBind(stepNoInput(u, end(u)), cont, fin),
                                                   finI)
      case HaveOutput(o, next, finO)  => HaveOutput(o, () => stepBind(next(), cont, fin), finO)
      case Delay(pipe, finD)          => Delay(() => stepBind(pipe(), cont, fin), finD)
    }

  private def stepFuse[U,I,X,M,O,R](up: GenPipe[U,I,X,M], down: GenPipe[M,X,O,R]): PipeCore[U,I,O,R] = {
    import Finalizer._
    def step(up: GenPipe[U,I,X,M], down: GenPipe[M,X,O,R], finUp: Finalizer): PipeCore[U,I,O,R] = {
      val downCore = stepPipe(down);
      val finPlus = finUp ++ downCore.finalizer;
      downCore match {
        case Done(r)    => Delay(() => { run(finPlus); Done(r) }, empty)
        case Delay(next, _)
                        => Delay(() => step(up, next(), finUp), finPlus)
        case HaveOutput(o, next, _)
                        => HaveOutput(o, () => step(up, next(), finUp), finPlus)
        case NeedInput(consO, endO, finDown) => {
          val upCore = stepPipe(up);
          val finUp = upCore.finalizer;
          val finPlus = finUp ++ finDown;
          upCore match {
            case Done(m)  => Delay(() => stepNoInput[M,O,R](m, endO(m)), finPlus)
            case Delay(next, _)
                          => Delay(() => step(next(), downCore, finUp), finPlus)
            case HaveOutput(x, next, finDown1)
                          => Delay(() => step(next(), consO(x), finUp), finPlus);
            case NeedInput(consI, endI, finDown1)
                          => NeedInput((i: I) => step(consI(i), downCore, finUp),
                                       (u: U) => step(stepNoInput(u, endI(u)), downCore, finUp),
                                       finPlus)
          }
        }
      }
    }
    step(up, down, empty);
  }

  private def stepNoInput[U,O,R](upResult: U, pipe: NoInput[U,O,R]): PipeCore[Any,Any,O,R] =
    stepFuse(done(upResult), pipe)
  /*
    stepPipe[U,Nothing,O,R](pipe) match {
      case p@Done(_)                => p;
      case NeedInput(_, e, fin)     => Delay(() => stepNoInput(upResult, e(upResult)), fin);
      case HaveOutput(o, next, fin) => HaveOutput(o, () => stepNoInput(upResult, next()), fin);
      case Delay(next, fin)         => Delay(() => stepNoInput(upResult, next()), fin);
    }
    */


  trait Monadic[U,I,O,R] extends Any {
    def flatMap[U2 <: U,I2 <: I,O2 >: O,B](f: R => GenPipe[U2,I2,O2,B])(implicit finalizer: Finalizer): GenPipe[U2,I2,O2,B];
    @inline
    final def >>=[U2 <: U,I2 <: I,O2 >: O,B](f: R => GenPipe[U2,I2,O2,B])(implicit finalizer: Finalizer): GenPipe[U2,I2,O2,B] = flatMap(f);
    @inline
    def map[B](f: R => B)(implicit finalizer: Finalizer) = flatMap((r: R) => done(f(r))): GenPipe[U,I,O,B];
    def >>[U2 <: U,I2 <: I,O2 >: O,B](p: => GenPipe[U2,I2,O2,B])(implicit finalizer: Finalizer): GenPipe[U2,I2,O2,B];
    def <<[U2 <: U,I2 <: I,O2 >: O](p: GenPipe[U2,I2,O2,Any])(implicit finalizer: Finalizer): GenPipe[U2,I2,O2,R];

    def >->[X,S](that: GenPipe[R,O,X,S]): GenPipe[U,I,X,S];
    def <-<[X,M](that: GenPipe[M,X,I,U]): GenPipe[M,X,O,R];
  }

  protected trait MonadicImpl[U,I,O,R] extends Any with Monadic[U,I,O,R] {
    def pipe: GenPipe[U,I,O,R];
    @inline def flatMap[U2 <: U,I2 <: I,O2 >: O,B](f: R => GenPipe[U2,I2,O2,B])(implicit finalizer: Finalizer) = Pipe.flatMap(pipe, f)
    //@inline def map[B](f: R => B)(implicit finalizer: Finalizer) = Pipe.map(pipe, f);
    @inline def >>[U2 <: U,I2 <: I,O2 >: O,B](p: => GenPipe[U2,I2,O2,B])(implicit finalizer: Finalizer) = Pipe.flatMap(pipe, (_:R) => p);
    @inline def <<[U2 <: U,I2 <: I,O2 >: O](p: GenPipe[U2,I2,O2,Any])(implicit finalizer: Finalizer) = Pipe.flatMap(p, (_:Any) => pipe);

    @inline def >->[X,S](that: GenPipe[R,O,X,S]) = Pipe.pipe(pipe, that);
    @inline def <-<[X,M](that: GenPipe[M,X,I,U]) = Pipe.pipe(that, pipe);
  }

  implicit class FlatMap[U,I,O,R](val pipe: GenPipe[U,I,O,R])
    extends AnyVal with MonadicImpl[U,I,O,R];
  implicit class FlatMapResultNothing[U,I,O](val pipe: GenPipe[U,I,O,Nothing])
    extends AnyVal with MonadicImpl[U,I,O,Nothing];
  implicit class FlatMapOutputNothing[U,I,R](val pipe: GenPipe[U,I,Nothing,R])
    extends AnyVal with MonadicImpl[U,I,Nothing,R];
}
