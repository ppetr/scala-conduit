import annotation.tailrec

sealed trait Pipe[I,O,R]
{
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1];
  def map[R1](f: R => R1): Pipe[I,O,R1];

  final def >->[X](that: Pipe[O,X,R]) = Pipe.pipe(this, that);
  final def <-<[X](that: Pipe[X,I,R]) = Pipe.pipe(that, this);
}
object Pipe {
  def apply[I,O,R](result: R): Pipe[I,O,R] = Done(result);

  def request[I,O,R]: Pipe[I,O,Option[I]] =
    NeedInput(i => Done(Some(i)));

  def respond[I,O,R](o: O): Pipe[I,O,Unit] =
    HaveOutput(o, _ => Done(()))

  /**
   * Composes two pipes, blocked on respond. This means that the second
   * <var>outp</var> pipe is executed until it needs input, then <var>inp</var>
   * is invoked.
   */
  def pipe[I,X,O,R](inp: Pipe[I,X,R], outp: Pipe[X,O,R]): Pipe[I,O,R] = {
    var i = inp;
    var o = outp;
    while (true) {
      val consume = outp match {
        case Done(r)              => return Done(r)
        case HaveOutput(o, next)  => return HaveOutput(o, _ => pipe(inp, next(())))
        case NeedInput(f)         => f;
      }
      inp match {
        case HaveOutput(x, next)  => { i = next(()); o = consume(x); }
        case Done(r)              => return Done(r)
        case NeedInput(c)         => return NeedInput((x: I) => pipe(c(x), outp));
      }
    }
    // Just to satisfy the compiler, we never get here.
    throw new IllegalStateException();
  }


  @tailrec
  def runPipe[R](pipe: Pipe[Unit,Nothing,R]): R =
    pipe match {
      case Done(r)            => r;
      case HaveOutput(o, _)   => o;
      case NeedInput(consume) => runPipe(consume(()));
    }

  def idP[A,R]: Pipe[A,A,R] =
    NeedInput(x => HaveOutput(x, _ => idP));
}

case class HaveOutput[I,O,R](output: O, next: Unit => Pipe[I,O,R])
    extends Pipe[I,O,R]
{
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1] =
    HaveOutput(output, _ => { next(()).flatMap(f) });
  def map[R1](f: R => R1): Pipe[I,O,R1] =
    HaveOutput(output, _ => { next(()).map(f) });
}

case class NeedInput[I,O,R](consume: I => Pipe[I,O,R])
    extends Pipe[I,O,R]
{
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1] =
    NeedInput(i => consume(i).flatMap(f));
  def map[R1](f: R => R1): Pipe[I,O,R1] =
    NeedInput(i => consume(i).map(f));
}

case class Done[I,O,R](result: R)
    extends Pipe[I,O,R] {
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1] =
    f(result);
  def map[R1](f: R => R1): Pipe[I,O,R1] =
    Done(f(result));
}
