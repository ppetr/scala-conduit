sealed trait Pipe[I,O,R]
{
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1];
  def map[R1](f: R => R1): Pipe[I,O,R1];

  final def >->[X](that: Pipe[O,X,R]) = Pipe.pipeI(this, that);
  final def <-<[X](that: Pipe[X,I,R]) = Pipe.pipeI(that, this);
}
object Pipe {
  def apply[I,O,R](result: R): Pipe[I,O,R] = Done(result);

  def request[I,O,R]: Pipe[I,O,Option[I]] =
    NeedInput(i => Done(Some(i)));

  def respond[I,O,R](o: O): Pipe[I,O,Unit] =
    HaveOutput(o, _ => Done(()))

  /**
   * Composes two pipes, blocked on respond.
   */
  def pipeI[I,X,O,R](inp: Pipe[I,X,R], outp: Pipe[X,O,R]): Pipe[I,O,R] =
    outp match {
      case Done(r)              => Done(r)
      case HaveOutput(o, next)  => HaveOutput(o, _ => pipeI(inp, next(())))
      case NeedInput(f)         => pipeO(inp, f);
    }

  /**
   * Composes two pipes, blocked on request.
   */
  def pipeO[I,X,O,R](inp: Pipe[I,X,R], outp: X => Pipe[X,O,R]): Pipe[I,O,R] =
    inp match {
      case Done(r)              => Done(r)
      case HaveOutput(o, next)  => pipeI(next(()), outp(o));
      case NeedInput(f)         => NeedInput(i => pipeO(f(i), outp));
    }
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
