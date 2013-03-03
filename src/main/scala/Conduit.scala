import annotation.tailrec
import collection.mutable.{ArrayBuffer, Buffer}

sealed trait Pipe[-I,+O,+R]
{
  /*
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1];
  final def >>[R1](p: => Pipe[I,O,R1]): Pipe[I,O,R1] = flatMap(_ => p);
  */
  final def map[R1](f: R => R1): Pipe[I,O,R1] = Pipe.map(this, f);

  /*
  final def >->[X](that: Pipe[O,X,R]) = Pipe.pipe(this, that);
  final def <-<[X](that: Pipe[X,I,R]) = Pipe.pipe(that, this);
  */

  final def forever[Rx]: Pipe[I,O,Rx] = Pipe.forever(this);
  final def finalizer(fin: => Unit): Pipe[I,O,R] = Pipe.delay(this, fin);
}

final case class HaveOutput[-I,+O,+R](output: O, next: () => Pipe[I,O,R])
    extends Pipe[I,O,R]
{
/*
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1] =
    HaveOutput(output, () => { next().flatMap(f) });
  def map[R1](f: R => R1): Pipe[I,O,R1] =
    HaveOutput(output, () => { next().map(f) });
  */
}

final case class NeedInput[-I,+O,R](consume: I => Pipe[I,O,R])
    extends Pipe[I,O,R]
{
  /*
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1] =
    NeedInput(i => consume(i).flatMap(f));
  def map[R1](f: R => R1): Pipe[I,O,R1] =
    NeedInput(i => consume(i).map(f));
  */
}

final case class Done[-I,+O,R](result: R)
    extends Pipe[I,O,R]
{
  /*
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1] =
    f(result);
  def map[R1](f: R => R1): Pipe[I,O,R1] =
    Done(f(result));
  */
}

final case class Delay[I,O,R](delayed: () => Pipe[I,O,R], finalizer: Option[() => Unit])
    extends Pipe[I,O,R]
{
/*
  def flatMap[R1](f: R => Pipe[I,O,R1]): Pipe[I,O,R1] =
    Delay(() => delayed().flatMap(f), finalizer);
  def map[R1](f: R => R1): Pipe[I,O,R1] =
    Delay(() => delayed().map(f), finalizer);
  */
}


object Pipe {
  def finish: Pipe[Any,Nothing,Unit] = finish(());
  def finish[R](result: => R): Pipe[Any,Nothing,R] = Done(result);

  def finalizer(finalizer: => Unit): Pipe[Any,Nothing,Unit]
    = Delay(() => finish, Some(() => finalizer));
  def delay[I,O,R](p: => Pipe[I,O,R]): Pipe[I,O,R]
    = Delay(() => p, None);
  def delay[I,O,R](p: => Pipe[I,O,R], finalizer: => Unit): Pipe[I,O,R]
    = Delay(() => p, Some(() => finalizer));

  def request[I]: Pipe[I,Nothing,I] =
    NeedInput(i => Done(i));

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

  def forever[I,O,R](p: Pipe[I,O,_]): Pipe[I,O,R] =
    andThen(p, { forever(p) });

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


  def runPipe[R](pipe: Pipe[Unit,Nothing,R]): R =
    runPipe(pipe, new ArrayBuffer);
  @tailrec
  private def runPipe[R](pipe: Pipe[Unit,Nothing,R], fins: Buffer[() => Unit]): R = {
    pipe match {
      case Done(r)            => for(f <- fins) { f(); }; r;
      case Delay(p, None)     => runPipe(p());
      case Delay(p, Some(fin))=> runPipe(p(), fins += fin);
      case HaveOutput(o, _)   => o;
      case NeedInput(consume) => runPipe(consume(()));
    }
  }

  def idP[A,Any]: Pipe[A,A,Any] =
    NeedInput(x => HaveOutput(x, () => { idP }));
}
