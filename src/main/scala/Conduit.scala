import annotation.tailrec
import collection.mutable.{ArrayBuffer, Buffer, ArrayStack, Stack, Queue}
import util.control.Exception

/**
 * Accepts input elements of type <code>I</code>, produces output elements of
 * type <code>O</code> and when finished, returns <code>R</code>.
 */
sealed trait Pipe[-I,+O,+R]
{
  final def as[I1 <: I, O1 >: O]: Pipe[I1,O1,R] = this;
  final def asI[I1 <: I]: Pipe[I1,O,R] = this;
  final def asO[O1 >: O]: Pipe[I,O1,R] = this;

  //final def map[R1](f: R => R1): Pipe[I,O,R1] = Pipe.map(this, f);

  //final def finalizer(fin: => Unit): Pipe[I,O,R] = Pipe.delay(this, fin);
}

/**
 * When a pipe is run, it's converted into a smaller, more specific set of
 * primitives, covered by <code>PipeCode</code>. It only allows input, output,
 * delay and producing the final result.
 */
private trait PipeCore[-I,+O,+R] extends Pipe[I,O,R] {
  def finalizer: Pipe.Finalizer;
}

private final case class HaveOutput[-I,+O,+R](output: O, next: () => Pipe[I,O,R], override val finalizer: Pipe.Finalizer)
  extends PipeCore[I,O,R];
private final case class NeedInput[-I,+O,+R](consume: I => Pipe[I,O,R], noInput: () => Pipe[Nothing,O,R], override val finalizer: Pipe.Finalizer)
  extends PipeCore[I,O,R];
private final case class Done[+R](result: R)
  extends PipeCore[Any,Nothing,R] {
    override def finalizer = Pipe.Finalizer.empty;
  }
private final case class Delay[-I,+O,+R](next: () => Pipe[I,O,R], override val finalizer: Pipe.Finalizer)
  extends PipeCore[I,O,R];

// We also have two more primitives that represent two core operations on
// pipes: binding and fusing. They are converted into the above ones when a
// pipe is run.

private final case class Bind[-I,+O,+R,S](first: Pipe[I,O,S], then: S => Pipe[I,O,R], finalizer: Pipe.Finalizer)
  extends Pipe[I,O,R];
private final case class Fuse[-I,X,+O,+R](up: Pipe[I,X,Any], down: Pipe[X,O,R])
  extends Pipe[I,O,R];
//private final case class Leftover[I,+O,+R](leftover: I, next: () => Pipe[I,O,R])
//  extends Pipe[I,O,R];


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

  type Catcher[+A] = Exception.Catcher[A];
  private object Catcher {
    def map[A,B](f: A => B, c: Catcher[A]): Catcher[B] =
      c andThen f;

    val empty: Catcher[Nothing] = new PartialFunction[Any,Nothing] {
      override def isDefinedAt(x: Any): Boolean = false;
      override def apply(x: Any): Nothing = throw new RuntimeException("undefined");
    };
    @inline
    def protect[R](catching: Catcher[R], body: => R): R =
      body; // TODO catching(body);
  }

  @inline
  protected val nextDone: () => Pipe[Any,Nothing,Unit] = () => finish;
  /*
  protected implicit def toFunStrict[A](body: A): () => A =
    () => body;
  protected implicit def toFunLazy[A](body: => A): () => A =
    () => body;
  */
  protected def const[A](body: => A): Any => A =
    (_) => body;

  /**
   * Returns a pipe that does nothing and returns <code>()</code>.
   */
  @inline
  val finish: Pipe[Any,Nothing,Unit] = finish(());
  /**
   * Returns a pipe that just returns the given result.
   */
  @inline
  def finish[R](result: R): Pipe[Any,Nothing,R] = Done(result);
  /**
   * Returns a pipe that runs the given finalizer and then returns the given result.
   */
  @inline
  def finish[R](result: R, fin: Finalizer): Pipe[Any,Nothing,R] = {
    Finalizer.run(fin);
    Done(result);
  }

  @inline
  def delay[I,O,R](inner: => Pipe[I,O,R])(implicit finalizer: Finalizer = Finalizer.empty): Pipe[I,O,R] =
    Delay(() => inner, finalizer);

  @inline
  def request[I]: Pipe[I,Nothing,Option[I]] =
    requestOpt((i: Option[I]) => Done(i))(Finalizer.empty);
  @inline
  def request[I,O,R](cont: I => Pipe[I,O,R], end: => Pipe[Any,O,R])(implicit finalizer: Finalizer): Pipe[I,O,R] =
    NeedInput(cont, () => end, finalizer);
  def requestOpt[I,O,R](cont: Option[I] => Pipe[I,O,R])(implicit finalizer: Finalizer): Pipe[I,O,R] =
    NeedInput((i: I) => cont(Some(i)), () => cont(None), finalizer);

  def requestU[I,O](cont: I => Pipe[I,O,Unit])(implicit finalizer: Finalizer = Finalizer.empty): Pipe[I,O,Unit] =
    requestE[I,O,Unit]((i: I) => cont(i), ());
  def requestE[I,O,R](cont: I => Pipe[I,O,R], end: => R)(implicit finalizer: Finalizer): Pipe[I,O,R] =
    NeedInput(cont, () => { finalizer.protect(finish(end)); }, finalizer);

  @inline
  def respond[I,O,R](o: O, cont: => Pipe[I,O,R])(implicit finalizer: Finalizer): Pipe[I,O,R] =
    HaveOutput(o, () => cont, finalizer);
  @inline
  def respond[O](o: O)(implicit finalizer: Finalizer): Pipe[Any,O,Unit] =
    HaveOutput(o, () => finish, finalizer);

/*
  @inline
  def leftover[I,O,R](left: I, pipe: => Pipe[I,O,R]): Pipe[I,O,R] =
    Leftover(left, pipe);
  @inline
  def leftover[I](left: I): Pipe[I,Nothing,Unit] =
    Leftover(left, nextDone);
*/

  @inline
  def flatMap[I,O,R,S](pipe: Pipe[I,O,S], f: S => Pipe[I,O,R])(implicit finalizer: Finalizer): Pipe[I,O,R] =
    Bind(pipe, f, finalizer);

  @inline
  def map[I,O,S,R](pipe: Pipe[I,O,S], f: S => R)(implicit finalizer: Finalizer): Pipe[I,O,R] =
    flatMap(pipe, (x: S) => finish(f(x)));


  def andThen[I,O,R](first: Pipe[I,O,_], then: => Pipe[I,O,R])(implicit finalizer: Finalizer): Pipe[I,O,R] =
    flatMap[I,O,R,Any](first, const(then));

  def until[I,O,A,B](f: A => Either[Pipe[I,O,A],B], start: A)(implicit finalizer: Finalizer): Pipe[I,O,B] = {
    def loop(x: A): Pipe[I,O,B] =
      f(start) match {
        case Left(pipe) => flatMap(pipe, loop _);
        case Right(b)   => Finalizer.run; finish(b);
      };
    delay(loop(start));
  }
  def until[I,O](pipe: => Option[Pipe[I,O,Any]])(implicit finalizer: Finalizer): Pipe[I,O,Unit] = {
    def loop(): Pipe[I,O,Unit] =
      pipe match {
        case Some(pipe) => andThen(pipe, loop());
        case None       => Finalizer.run; finish;
      }
    delay { loop() }
  }

  @inline
  def blockInput[O,R](p: Pipe[Nothing,O,R]): Pipe[Any,O,R] =
    pipe(finish, p);
 

  /**
   * The pipes returned by <code>f</code> should not request any input. If they do,
   * they're terminated.
   */
  @inline
  def unfold[I,O](f: I => Pipe[Nothing,O,Any])(implicit finalizer: Finalizer): Pipe[I,O,Unit] =
    requestU[I,O](i => andThen(blockInput(f(i)), unfold(f)));

  def repeat[O](produce: => O)(implicit finalizer: Finalizer): Pipe[Any,O,Nothing] = {
    def loop(): Pipe[Any,O,Nothing]
      = respond(produce, loop());
    delay { loop() }
  }



  @inline
  def pipe[I,X,O,R](i: Pipe[I,X,Any], o: Pipe[X,O,R]): Pipe[I,O,R] =
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


  def runPipe[R](pipe: Pipe[Nothing,Nothing,R]): R = {
    import Finalizer._
    @tailrec
    def step[R](pipe: Pipe[Nothing,Nothing,R]): R = {
      stepPipe[Nothing,Nothing,R](pipe) match {
        case Done(r)                  => r;
        case HaveOutput(o, _, _)      => o; // Never occurs - o is Nothing so it can be typed to anything.
        case NeedInput(_, end, fin)   => step(fin.protect({ end() }));
        case Delay(next, fin)         => step(fin.protect({ next() }));
      }
    }
    step(pipe);
  }


  private def stepPipe[I,O,R](pipe: Pipe[I,O,R]): PipeCore[I,O,R] =
    pipe match {
      case p@Done(_)              => p;
      case p@NeedInput(_,_,_)     => p;
      case p@HaveOutput(o,_,_)    => p;
      case p@Delay(_,_)           => p;
      case Fuse(up, down)         => stepFuse(up, down);
      case Bind(next, then, fin)  => stepBind(next, then, fin);
    }

  private def stepBind[I,O,R,S](pipe: Pipe[I,O,S], then: S => Pipe[I,O,R], fin: Finalizer): PipeCore[I,O,R] =
    stepPipe(pipe) match {
      case Done(s)                    => Delay(() => then(s), fin)
      case NeedInput(cons, end, finI) => NeedInput(i  => stepBind(cons(i), then, fin),
                                                   () => stepBind(stepNoInput(end()), then, fin),
                                                   finI)
      case HaveOutput(o, next, finO)  => HaveOutput(o, () => stepBind(next(), then, fin), finO)
      case Delay(pipe, finD)          => Delay(() => stepBind(pipe(), then, fin), finD)
    }

  private def stepFuse[I,X,O,R](up: Pipe[I,X,Any], down: Pipe[X,O,R]): PipeCore[I,O,R] = {
    import Finalizer._
    def step(up: Pipe[I,X,Any], down: Pipe[X,O,R], finUp: Finalizer): PipeCore[I,O,R] = {
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
            case Done(_)  => stepNoInput(downCore);
            case Delay(next, _)
                          => Delay(() => step(next(), downCore, finUp), finPlus)
            case HaveOutput(x, next, finDown1)
                          => Delay(() => step(next(), consO(x), finUp), finPlus);
            case NeedInput(consI, endI, finDown1)
                          => NeedInput((i: I) => step(consI(i), downCore, finUp),
                                       ()     => step(stepNoInput(endI()), downCore, finUp),
                                       finPlus)
          }
        }
      }
    }
    step(up, down, empty);
  }

  private def stepNoInput[O,R](pipe: Pipe[Nothing,O,R]): PipeCore[Any,O,R] =
    stepPipe[Nothing,O,R](pipe) match {
      case p@Done(_)                => p;
      case NeedInput(_, e, fin)     => Delay(() => stepNoInput(e()), fin);
      case HaveOutput(o, next, fin) => HaveOutput(o, () => stepNoInput(next()), fin);
      case Delay(next, fin)         => Delay(() => stepNoInput(next()), fin);
    }

//    pipe(i, o, mergeEither[R] _);
//  private def mergeEither[A](e: Either[A,A]): A =
//    e match {
//      case Left(x)  => x
//      case Right(x) => x
//    }
//  /**
//   * Composes two pipes, blocked on respond. This means that the second
//   * <var>outp</var> pipe is executed until it needs input, then <var>inp</var>
//   * is invoked.
//   */
//  def pipe[I,X,O,R1,R2,R](i: Pipe[I,X,R1], o: Pipe[X,O,R2], end: Either[R1,R2] => R): Pipe[I,O,R] = {
//    @tailrec
//    def step(i: Pipe[I,X,R1], o: Pipe[X,O,R2], fins: List[RunOnce]): Pipe[I,O,R] = {
//      val consume = o match {
//        case Done(r)              => RunOnce.run(fins); return Done(end(Right(r)))
//        case Delay(o1, fin)       => return Delay(() => stepCont(i, o1(), fin :: fins), fin);
//        case HaveOutput(o, next)  => return HaveOutput(o, () => stepCont(i, next(), fins) )
//        case NeedInput(f)         => f
//      }
//      i match {
//        case HaveOutput(x, next)  => step(next(), consume(x), fins);
//        case Delay(i1, fin)       => return Delay(() => stepCont(i1(), o, fin :: fins), fin);
//        case Done(r)              => RunOnce.run(fins); return Done(end(Left(r)))
//        case NeedInput(c)         => return NeedInput((x: I) => stepCont(c(x), o, fins));
//      }
//    }
//    def stepCont(inp: Pipe[I,X,R1], outp: Pipe[X,O,R2], fins: List[RunOnce])
//      = step(inp, outp, fins);
//    stepCont(i, o, Nil);
//  }
//
//
//  def runPipe[R](pipe: Pipe[Unit,Nothing,R]): R = {
//    @tailrec
//    def step[R](pipe: Pipe[Unit,Nothing,R], fins: ArrayStack[RunOnce]): R = {
//      pipe match {
//        case Done(r)            => r;
//        case Delay(p, fin)      => step(p(), fins += fin);
//        case HaveOutput(o, _)   => o; // Never occurs - o is Nothing so it can be typed to anything.
//        case NeedInput(consume) => step(consume(()), fins);
//      }
//    }
//    def stepCont[R](pipe: Pipe[Unit,Nothing,R], fins: ArrayStack[RunOnce]): R =
//      step(pipe, fins);
//
//    val fins = new ArrayStack[RunOnce];
//    try {
//      step(pipe, fins);
//    } finally {
//      fins.foreach(_.apply());
//    }
//  }




  implicit def pipeFlatMap[I,O,A](pipe: Pipe[I,O,A])(implicit finalizer: Finalizer) = new {
    @inline def flatMap[B](f: A => Pipe[I,O,B]) = Pipe.flatMap(pipe, f)
    @inline def map[B](f: A => B) = flatMap((r: A) => finish(f(r)));
    @inline def >>[B](p: => Pipe[I,O,B]): Pipe[I,O,B] = flatMap(_ => p);

    @inline def >->[X](that: Pipe[O,X,A]) = Pipe.pipe(pipe, that);
    @inline def <-<[X](that: Pipe[X,I,A]) = Pipe.pipe(that, pipe);
  }


//  /**
//   * Executes the given finalizer only the first time {@link #apply()} is called.
//   * This class is <em>not</em> synchronized.
//   */
//  class RunOnce(actions: Iterator[Catcher])
//    extends Catcher
//  {
//    def this(actions: Iterable[Catcher]) { this(actions.iterator); }
//
//    override def apply(): Unit = {
//      while (actions.hasNext)
//        actions.next().apply();
//    }
//  }
//  object RunOnce {
//    object Empty extends RunOnce(Iterator.empty);
//    def apply(actions: Iterable[Catcher]): RunOnce =
//      if (actions.isEmpty) Empty else new RunOnce(actions);
//    def apply(actions: Catcher*): RunOnce =
//      apply(actions : Iterable[Catcher]);
//
//    def run(fins: Iterable[RunOnce]) =
//      fins.foreach(_.apply);
//  }
}
