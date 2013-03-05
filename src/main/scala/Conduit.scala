import annotation.tailrec
import collection.mutable.{ArrayBuffer, Buffer, ArrayStack, Stack}

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
final case class NeedInput[-I,+O,+R](consume: I => Pipe[I,O,R])
    extends Pipe[I,O,R];
final case class Done[+R](result: R)
    extends Pipe[Any,Nothing,R];
final case class Delay[-I,+O,+R](delayed: () => Pipe[I,O,R], finalizer: Pipe.RunOnce)
    extends Pipe[I,O,R];


object Pipe {
  type Finalizer = () => Unit;

  val finish: Pipe[Any,Nothing,Unit] = finish(());
  def finish[R](result: R): Pipe[Any,Nothing,R] = Done(result);

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
  final def flatMap[I,O,Ri,R](p: Pipe[I,O,Ri], f: Ri => Pipe[I,O,R]): Pipe[I,O,R] =
    flatMap(p, f, new ArrayBuffer);
  private def flatMap[I,O,Ri,R](p: Pipe[I,O,Ri], f: Ri => Pipe[I,O,R], fins: Buffer[RunOnce]): Pipe[I,O,R] =
    p match {
      case HaveOutput(out, next)      => HaveOutput(out, () => flatMap(next(), f, fins));
      case NeedInput(consume)         => NeedInput(x => flatMap(consume(x), f, fins));
      case Delay(delayed, fin)        => Delay(() => flatMap(delayed(), f, fins += fin), fin);
      case Done(result)               => { RunOnce.run(fins); f(result); }
    }


  def map[I,O,Ri,R](p: Pipe[I,O,Ri], f: Ri => R): Pipe[I,O,R] =
    flatMap[I,O,Ri,R](p, (x: Ri) => Done(f(x)));
  def andThen[I,O,R](p: Pipe[I,O,_], q: => Pipe[I,O,R]): Pipe[I,O,R] =
    flatMap[I,O,Any,R](p, (_: Any) => q);

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



  def pipe[I,X,O,R](i: Pipe[I,X,R], o: Pipe[X,O,R]): Pipe[I,O,R] =
    pipe(i, o, mergeEither[R] _);
  private def mergeEither[A](e: Either[A,A]): A =
    e match {
      case Left(x)  => x
      case Right(x) => x
    }
  /**
   * Composes two pipes, blocked on respond. This means that the second
   * <var>outp</var> pipe is executed until it needs input, then <var>inp</var>
   * is invoked.
   */
  def pipe[I,X,O,R1,R2,R](i: Pipe[I,X,R1], o: Pipe[X,O,R2], end: Either[R1,R2] => R): Pipe[I,O,R] = {
    @tailrec
    def step(i: Pipe[I,X,R1], o: Pipe[X,O,R2], fins: List[RunOnce]): Pipe[I,O,R] = {
      val consume = o match {
        case Done(r)              => RunOnce.run(fins); return Done(end(Right(r)))
        case Delay(o1, fin)       => return Delay(() => stepCont(i, o1(), fin :: fins), fin);
        case HaveOutput(o, next)  => return HaveOutput(o, () => stepCont(i, next(), fins) )
        case NeedInput(f)         => f
      }
      i match {
        case HaveOutput(x, next)  => step(next(), consume(x), fins);
        case Delay(i1, fin)       => return Delay(() => stepCont(i1(), o, fin :: fins), fin);
        case Done(r)              => RunOnce.run(fins); return Done(end(Left(r)))
        case NeedInput(c)         => return NeedInput((x: I) => stepCont(c(x), o, fins));
      }
    }
    def stepCont(inp: Pipe[I,X,R1], outp: Pipe[X,O,R2], fins: List[RunOnce])
      = step(inp, outp, fins);
    stepCont(i, o, Nil);
  }


  def runPipe[R](pipe: Pipe[Unit,Nothing,R]): R = {
    @tailrec
    def step[R](pipe: Pipe[Unit,Nothing,R], fins: ArrayStack[RunOnce]): R = {
      pipe match {
        case Done(r)            => r;
        case Delay(p, fin)      => step(p(), fins += fin);
        case HaveOutput(o, _)   => o; // Never occurs - o is Nothing so it can be typed to anything.
        case NeedInput(consume) => step(consume(()), fins);
      }
    }
    def stepCont[R](pipe: Pipe[Unit,Nothing,R], fins: ArrayStack[RunOnce]): R =
      step(pipe, fins);

    val fins = new ArrayStack[RunOnce];
    try {
      step(pipe, fins);
    } finally {
      fins.foreach(_.apply());
    }
  }


  def idP[A]: Pipe[A,A,Nothing] =
    NeedInput(x => HaveOutput(x, () => idP));

  def arrP[I,O](f: I => O): Pipe[I,O,Nothing] = {
    def loop(): Pipe[I,O,Nothing] =
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
    def apply(actions: Iterable[Finalizer]): RunOnce =
      if (actions.isEmpty) Empty else new RunOnce(actions);
    def apply(actions: Finalizer*): RunOnce =
      apply(actions : Iterable[Finalizer]);

    def run(fins: Iterable[RunOnce]) =
      fins.foreach(_.apply);
  }
}
