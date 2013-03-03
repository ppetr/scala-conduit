import annotation.tailrec
import collection.mutable.{ArrayBuffer, Buffer}

sealed trait Pipe[-I,+O,+R]
{
  final def as[I1 <: I, O1 >: O]: Pipe[I1,O1,R] = this;
  final def asI[I1 <: I]: Pipe[I1,O,R] = this;
  final def asO[O1 >: O]: Pipe[I,O1,R] = this;

  final def map[R1](f: R => R1): Pipe[I,O,R1] = Pipe.map(this, f);

  final def forever: Pipe[I,O,Nothing] = Pipe.forever(this);
  final def finalizer(fin: => Unit): Pipe[I,O,R] = Pipe.delay(this, fin);
}

final case class HaveOutput[-I,+O,+R](output: O, next: () => Pipe[I,O,R])
    extends Pipe[I,O,R];
final case class NeedInput[-I,+O,R](consume: I => Pipe[I,O,R])
    extends Pipe[I,O,R];
final case class Done[-I,+O,R](result: R)
    extends Pipe[I,O,R];
final case class Delay[I,O,R](delayed: () => Pipe[I,O,R], finalizer: Pipe.RunOnce)
    extends Pipe[I,O,R];


object Pipe {
  type Finalizer = () => Unit;

  def finish: Pipe[Any,Nothing,Unit] = finish(());
  def finish[R](result: => R): Pipe[Any,Nothing,R] = Done(result);

  def finalizer(fin: => Unit): Pipe[Any,Nothing,Unit]
    = delay(finish, RunOnce(() => fin));
  def delay[I,O,R](p: => Pipe[I,O,R]): Pipe[I,O,R]
    = delay(p, RunOnce.Empty);
  def delay[I,O,R](p: => Pipe[I,O,R], finalizer: => Unit): Pipe[I,O,R]
    = delay(p, RunOnce(() => finalizer));
  def delay[I,O,R](p: => Pipe[I,O,R], finalizer: Iterable[Finalizer]): Pipe[I,O,R]
    = delay(p, RunOnce(finalizer));
  protected def delay[I,O,R](p: => Pipe[I,O,R], finalizer: RunOnce): Pipe[I,O,R]
    = Delay(() => p, finalizer);

  def request[I]: Pipe[I,Nothing,I] =
    request(i => Done(i));
  def request[I,O,R](cont: I => Pipe[I,O,R]): Pipe[I,O,R] =
    NeedInput(cont);

  def respond[I,O,R](o: O, cont: => Pipe[I,O,R]): Pipe[I,O,R] =
    HaveOutput(o, () => cont);
  def respond[O](o: O): Pipe[Any,O,Unit] =
    HaveOutput(o, () => { Done(()) })

  //@tailrec
  def flatMap[I,O,Ri,R](p: Pipe[I,O,Ri], f: Ri => Pipe[I,O,R]): Pipe[I,O,R] =
    p match {
      case HaveOutput(out, next)      => HaveOutput(out, () => flatMap(next(), f));
      case NeedInput(consume)         => NeedInput(x => flatMap(consume(x), f));
      case Delay(delayed, fin)        => Delay(() => flatMap(delayed(), f), fin);
      case Done(result)               => f(result);
    }
  def map[I,O,Ri,R](p: Pipe[I,O,Ri], f: Ri => R): Pipe[I,O,R] =
    flatMap[I,O,Ri,R](p, x => Done(f(x)));
  def andThen[I,O,R](p: Pipe[I,O,_], q: => Pipe[I,O,R]): Pipe[I,O,R] =
    flatMap[I,O,Any,R](p, _ => q);

  def forever[I,O](p: Pipe[I,O,_]): Pipe[I,O,Nothing] =
    andThen(p, { forever(p) });

  def until[I,O,A,B](f: A => Either[Pipe[I,O,A],B], start: A): Pipe[I,O,B] = {
    def loop(x: A): Pipe[I,O,B] =
      f(start) match {
        case Left(pipe) => flatMap(pipe, loop _);
        case Right(b)   => finish(b);
      };
    loop(start);
  }
  def until[I,O](pipe: => Option[Pipe[I,O,Any]]): Pipe[I,O,Unit] = {
    def loop(): Pipe[I,O,Unit] =
      pipe match {
        case Some(pipe) => andThen(pipe, loop());
        case None       => finish;
      }
    loop();
  }

  def unfold[I,O,A](f: I => Pipe[Any,O,A]): Pipe[I,O,Nothing] =
    request[I,O,A](f).forever;

  def repeat[O](produce: => O): Pipe[Any,O,Nothing] = {
    def loop(): Pipe[Any,O,Nothing]
      = HaveOutput(produce, loop _);
    delay(loop());
  }


  /**
   * Composes two pipes, blocked on respond. This means that the second
   * <var>outp</var> pipe is executed until it needs input, then <var>inp</var>
   * is invoked.
   */
  def pipe[I,X,O,R](inp: Pipe[I,X,R], outp: Pipe[X,O,R]): Pipe[I,O,R] = {
    var i = inp;
    var o = outp;
    while (true) {
      val consume = o match {
        case Done(r)              => return Done(r)
        case Delay(o1, fin)       => return Delay(() => pipe(i, o1()), fin);
        case HaveOutput(o, next)  => return HaveOutput(o, () => { pipe(i, next()) } )
        case NeedInput(f)         => f
      }
      i match {
        case HaveOutput(x, next)  => { i = next(); o = consume(x); }
        case Delay(i1, fin)       => return Delay(() => pipe(i1(), o), fin);
        case Done(r)              => return Done(r)
        case NeedInput(c)         => return NeedInput((x: I) => pipe(c(x), o));
      }
    }
    // Just to satisfy the compiler, we never get here.
    throw new IllegalStateException();
  }


  def runPipe[R](pipe: Pipe[Unit,Nothing,R]): R = {
    val fins = new ArrayBuffer[RunOnce]
    try {
      runPipe(pipe, fins);
    } finally {
      fins.foreach(_.apply());
    }
  }
  /**
   * Note: Doesn't run the finalizers!
   */
  @tailrec
  private def runPipe[R](pipe: Pipe[Unit,Nothing,R], fins: Buffer[RunOnce]): R = {
    pipe match {
      case Done(r)            => r;
      case Delay(p, fin)      => runPipe(p(), fins += fin);
      case HaveOutput(o, _)   => o;
      case NeedInput(consume) => runPipe(consume(()));
    }
  }

  def idP[A]: Pipe[A,A,Any] =
    NeedInput(x => HaveOutput(x, () => idP));

  def arrP[I,O](f: I => O): Pipe[I,O,Any] = {
    def loop(): Pipe[I,O,Any] =
      NeedInput(x => HaveOutput(f(x), loop _));
    loop();
  }


  implicit def pipeFlatMap[I,O,A](pipe: Pipe[I,O,A]) = new {
    def flatMap[B](f: A => Pipe[I,O,B]) = Pipe.flatMap(pipe, f)
    def >>[B](p: => Pipe[I,O,B]): Pipe[I,O,B] = flatMap(_ => p);

    def >->[X](that: Pipe[O,X,A]) = Pipe.pipe(pipe, that);
    def <-<[X](that: Pipe[X,I,A]) = Pipe.pipe(that, pipe);
  }


  /**
   * Executes the given finalizer only the first time {@link #apply()} is called.
   * This class is <em>not</em> synchronized.
   */
  class RunOnce(actions: Iterator[Finalizer])
    extends Finalizer
  {
    def this(actions: Iterable[Finalizer]) { this(actions.iterator); }

    override def apply(): Unit = {
      while (actions.hasNext)
        actions.next().apply();
    }
  }
  object RunOnce {
    object Empty extends RunOnce(Iterator.empty);
    def apply[A](actions: Iterable[Finalizer]): RunOnce =
      if (actions.isEmpty) Empty else new RunOnce(actions);
    def apply[A](actions: Finalizer*): RunOnce =
      apply(actions : Iterable[Finalizer]);
  }
}
