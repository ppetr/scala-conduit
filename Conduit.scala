import annotation.tailrec

sealed trait Pipe[I,O,R]
{
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1];
  final def >>[R1](p: => Pipe[I,O,R1]): Pipe[I,O,R1] = flatMap(_ => p);
  def map[R1](f: R => R1): Pipe[I,O,R1];

  final def >->[X](that: Pipe[O,X,R]) = Pipe.pipe(this, that);
  final def <-<[X](that: Pipe[X,I,R]) = Pipe.pipe(that, this);

  final def forever[R]: Pipe[I,O,R] = Pipe.forever(this);

  implicit def extend[I1 <: I,O1 >: O]: Pipe[I1,O1,R];
}

case class HaveOutput[I,O,R](output: O, next: () => Pipe[I,O,R])
    extends Pipe[I,O,R]
{
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1] =
    HaveOutput(output, () => { next().flatMap(f) });
  def map[R1](f: R => R1): Pipe[I,O,R1] =
    HaveOutput(output, () => { next().map(f) });

  def extend[I1 <: I,O1 >: O]: Pipe[I1,O1,R] =
    HaveOutput(output, () => { next().extend });
}

case class NeedInput[I,O,R](consume: I => Pipe[I,O,R])
    extends Pipe[I,O,R]
{
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1] =
    NeedInput(i => consume(i).flatMap(f));
  def map[R1](f: R => R1): Pipe[I,O,R1] =
    NeedInput(i => consume(i).map(f));

  def extend[I1 <: I,O1 >: O]: Pipe[I1,O1,R] =
    NeedInput(x => { consume(x).extend });
}

case class Done[I,O,R](result: R)
    extends Pipe[I,O,R]
{
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1] =
    f(result);
  def map[R1](f: R => R1): Pipe[I,O,R1] =
    Done(f(result));

  def extend[I1,O1]: Pipe[I1,O1,R] =
    Done(result);
}

case class Delay[I,O,R](delayed: () => Pipe[I,O,R])
    extends Pipe[I,O,R]
{
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1] =
    Delay(() => delayed().flatMap(f));
  def map[R1](f: R => R1): Pipe[I,O,R1] =
    Delay(() => delayed().map(f));

  def extend[I1 <: I,O1 >: O]: Pipe[I1,O1,R] =
    Delay(() => delayed().extend);
}


object Pipe {
  def finish[I,O]: Pipe[I,O,Unit] = Done(());
  def finish[I,O,R](result: => R): Pipe[I,O,R] = Done(result);

  def delay[I,O,R](p: => Pipe[I,O,R]): Pipe[I,O,R] = Delay(() => p);

  def request[I,O]: Pipe[I,O,I] =
    NeedInput(i => Done(i));

  def respond[I,O](o: O): Pipe[I,O,Unit] =
    HaveOutput(o, () => { Done(()) })

  def forever[I,O,R](p: Pipe[I,O,_]): Pipe[I,O,R] =
    p >> forever(p);

  /**
   * Composes two pipes, blocked on respond. This means that the second
   * <var>outp</var> pipe is executed until it needs input, then <var>inp</var>
   * is invoked.
   */
  def pipe[I,X,O,R](inp: Pipe[I,X,R], outp: Pipe[X,O,R]): Pipe[I,O,R] = {
    var i = inp;
    var o = outp;
    while (true) {
      o match {
        case Done(r)              => return Done(r)
        case Delay(o1)            => o = o1();
        case HaveOutput(o, next)  => return HaveOutput(o, () => { pipe(i, next()) } )
        case NeedInput(consume)   =>
          i match {
            case HaveOutput(x, next)  => { i = next(); o = consume(x); }
            case Delay(i1)            => i = i1();
            case Done(r)              => return Done(r)
            case NeedInput(c)         => return NeedInput((x: I) => pipe(c(x), o));
          }
      }
    }
    // Just to satisfy the compiler, we never get here.
    throw new IllegalStateException();
  }


  @tailrec
  def runPipe[R](pipe: Pipe[Unit,Nothing,R]): R = {
    pipe match {
      case Done(r)            => r;
      case Delay(p)           => runPipe(p());
      case HaveOutput(o, _)   => o;
      case NeedInput(consume) => runPipe(consume(()));
    }
  }

  def idP[A,R]: Pipe[A,A,R] =
    NeedInput(x => HaveOutput(x, () => { idP }));
}
