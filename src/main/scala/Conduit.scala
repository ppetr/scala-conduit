import annotation.tailrec
import collection.mutable.{ArrayBuffer, Buffer, ArrayStack, Stack, Queue}
import util.control.Exception

sealed trait Pipe[-I,+O,+R]
{
  final def as[I1 <: I, O1 >: O]: Pipe[I1,O1,R] = this;
  final def asI[I1 <: I]: Pipe[I1,O,R] = this;
  final def asO[O1 >: O]: Pipe[I,O1,R] = this;

  //final def map[R1](f: R => R1): Pipe[I,O,R1] = Pipe.map(this, f);

  //final def forever: Pipe[I,O,Nothing] = Pipe.forever(this);
  //final def finalizer(fin: => Unit): Pipe[I,O,R] = Pipe.delay(this, fin);
}

private trait PipeCore[-I,+O,+R] extends Pipe[I,O,R];

private final case class HaveOutput[-I,+O,+R](output: O, next: () => Pipe[I,O,R])
  extends PipeCore[I,O,R];
private final case class NeedInput[-I,+O,+R](consume: I => Pipe[I,O,R])
  extends PipeCore[I,O,R];
private final case class Done[+R](result: R)
  extends PipeCore[Any,Nothing,R];

private final case class Bind[-I,+O,+R,S](first: () => Pipe[I,O,S], catching: Pipe.Catcher[S], then: S => Pipe[I,O,R])
  extends Pipe[I,O,R];
private final case class Fuse[-I,X,+O,+R,Rr,Rl](up: Pipe[I,X,Rl], down: Pipe[X,O,Rr], merge: Either[Rl,Rr] => R)
  extends Pipe[I,O,R];
//private final case class Leftover[I,+O,+R](leftover: I, next: () => Pipe[I,O,R])
//  extends Pipe[I,O,R];


object Pipe {
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

  @inline
  val finish: Pipe[Any,Nothing,Unit] = finish(());
  @inline
  def finish[R](result: R): Pipe[Any,Nothing,R] = Done(result);

  @inline
  def catching[I,O,R](catching: Catcher[R], inner: => Pipe[I,O,R]): Pipe[I,O,R] =
    Bind(() => inner, catching, (x: R) => Done(x));
  @inline
  def finalizer[I,O,R](fin: => Unit, inner: => Pipe[I,O,R]): Pipe[I,O,R] =
    // TODO
    inner;
  @inline
  def delay[I,O,R](p: => Pipe[I,O,R]): Pipe[I,O,R] =
    Bind(() => finish, Catcher.empty, const(p));

  @inline
  def request[I]: Pipe[I,Nothing,I] =
    request(i => Done(i));
  @inline
  def request[I,O,R](cont: I => Pipe[I,O,R]): Pipe[I,O,R] =
    NeedInput(cont);

  @inline
  def respond[I,O,R](o: O, cont: => Pipe[I,O,R]): Pipe[I,O,R] =
    HaveOutput(o, () => cont);
  @inline
  def respond[O](o: O): Pipe[Any,O,Unit] =
    HaveOutput(o, () => finish);

/*
  @inline
  def leftover[I,O,R](left: I, pipe: => Pipe[I,O,R]): Pipe[I,O,R] =
    Leftover(left, pipe);
  @inline
  def leftover[I](left: I): Pipe[I,Nothing,Unit] =
    Leftover(left, nextDone);
*/

  @inline
  def flatMap[I,O,R,S](pipe: Pipe[I,O,S], f: S => Pipe[I,O,R]): Pipe[I,O,R] =
    Bind(() => pipe, Catcher.empty, f);

  @inline
  def map[I,O,S,R](pipe: Pipe[I,O,S], f: S => R): Pipe[I,O,R] =
    flatMap(pipe, (x: S) => finish(f(x)));


  def andThen[I,O,R](first: Pipe[I,O,_], then: => Pipe[I,O,R]): Pipe[I,O,R] =
    flatMap[I,O,R,Any](first, const(then));

  def forever[I,O](p: Pipe[I,O,_]): Pipe[I,O,Nothing] =
    andThen(p, { forever(p) });

  def until[I,O,A,B](f: A => Either[Pipe[I,O,A],B], start: A): Pipe[I,O,B] = {
    def loop(x: A): Pipe[I,O,B] =
      f(start) match {
        case Left(pipe) => flatMap(pipe, loop _);
        case Right(b)   => finish(b);
      };
    delay(loop(start));
  }
  def until[I,O](pipe: => Option[Pipe[I,O,Any]]): Pipe[I,O,Unit] = {
    def loop(): Pipe[I,O,Unit] =
      pipe match {
        case Some(pipe) => andThen(pipe, loop());
        case None       => finish;
      }
    delay { loop() }
  }

  @inline
  def blockInput[I,O,R](ifRequest: Pipe[I,O,R], p: Pipe[Nothing,O,R]): Pipe[I,O,R] =
    flatMap(pipeE(finish, p), (r: Either[Unit,R]) => r match {
      case Left(_)  => ifRequest;
      case Right(r) => finish(r);
    });
  @inline
  def blockInput[O,R](ifRequest: R, p: Pipe[Nothing,O,R]): Pipe[Any,O,R] =
    pipe(finish(ifRequest), p);
  @inline
  def blockInput[O,R](p: Pipe[Nothing,O,R]): Pipe[Any,O,Any] =
    pipe(finish, p);
 

  /**
   * The pipes returned by <code>f</code> should not request any input. If they do,
   * they're terminated.
   */
  @inline
  def unfold[I,O,A](f: I => Pipe[Any,O,A]): Pipe[I,O,Nothing] =
    forever(request[I,O,Any](i => blockInput(f(i))));

  def repeat[O](produce: => O): Pipe[Any,O,Nothing] = {
    def loop(): Pipe[Any,O,Nothing]
      = respond(produce, loop());
    delay { loop() }
  }



  @inline
  def pipe[I,X,O,Rl,Rr,R](i: Pipe[I,X,Rl], o: Pipe[X,O,Rr], merge: Either[Rl,Rr] => R): Pipe[I,O,R] =
    Fuse(i, o, merge);
  def pipeE[I,X,O,Rl,Rr](i: Pipe[I,X,Rl], o: Pipe[X,O,Rr]): Pipe[I,O,Either[Rl,Rr]] =
    pipe(i, o, (x: Either[Rl,Rr]) => x);
  def pipe[I,X,O,R](i: Pipe[I,X,R], o: Pipe[X,O,R]): Pipe[I,O,R] =
    pipe(i, o, (x: Either[R,R]) => x match {
      case Left(x)  => x;
      case Right(x) => x;
    });


  def idP[A]: Pipe[A,A,Nothing] =
    request(x => respond(x, idP));

  def mapP[I,O](f: I => O): Pipe[I,O,Nothing] =
    request(x => respond(f(x), mapP(f)));


  sealed trait Leftover[+I,+R] { val result: R; }
  final case class HasLeft[+I,+R](left: I, override val result: R)
    extends Leftover[I,R];
  final case class NoLeft[+R](override val result: R)
    extends Leftover[Nothing,R];


  def runPipe[R](pipe: Pipe[Unit,Nothing,R]): R = {
    @tailrec
    def step[R](pipe: Pipe[Unit,Nothing,R]): R = {
      stepPipe(pipe) match {
        case Done(r)               => r;
        case HaveOutput(o, _)      => o; // Never occurs - o is Nothing so it can be typed to anything.
        case NeedInput(consume)    => step(consume(()));
      }
    }
    step(pipe);
  }


  @tailrec
  private def stepPipe[I,O,R](pipe: Pipe[I,O,R]): PipeCore[I,O,R] =
    pipe match {
      case p@Done(_)              => p;
      case p@NeedInput(_)         => p;
      case p@HaveOutput(o,_)      => p;
      case Fuse(up, down, end)    => stepFuse(up, down, end);
      case Bind(next, fin, then)  => stepPipe(stepBind(next, fin, then));
    }

  private def stepBind[I,O,R,S](pipe: () => Pipe[I,O,S], fin: Catcher[S], then: S => Pipe[I,O,R]): Pipe[I,O,R] = {
    Catcher.protect(Catcher.map(then, fin), {
      stepPipe(pipe()) match {
        case Done(s)              => then(s);
        case NeedInput(cons)      => NeedInput(i => stepBind(() => cons(i), fin, then));
        case HaveOutput(o, next)  => HaveOutput(o, () => stepBind(next, fin, then));
      }
    })
  }

  private def stepFuse[I,X,O,Ru,Rd,R](up: Pipe[I,X,Ru], down: Pipe[X,O,Rd], end: Either[Ru,Rd] => R): PipeCore[I,O,R] = {
    @tailrec
    def step(up: Pipe[I,X,Ru], down: Pipe[X,O,Rd]): PipeCore[I,O,R] =
      stepPipe(down) match {
        case Done(r)              => Done(end(Right(r)));
        case HaveOutput(o, next)  => HaveOutput(o, () => stepFuse(up, next(), end));
        case NeedInput(consO)     =>
          stepPipe(up) match {
            case Done(r)              => Done(end(Left(r)));
            case HaveOutput(x, next)  => step(next(), consO(x));
            case NeedInput(consI)     => NeedInput((i: I) => stepFuse(consI(i), down, end));
          }
        }
    step(up, down);
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




  implicit def pipeFlatMap[I,O,A](pipe: Pipe[I,O,A]) = new {
    @inline def flatMap[B](f: A => Pipe[I,O,B]) = Pipe.flatMap(pipe, f)
    @inline def map[B](f: A => B) = flatMap((r: A) => finish(f(r)));
    @inline def >>[B](p: => Pipe[I,O,B]): Pipe[I,O,B] = flatMap(_ => p);

    @inline def >->[X](that: Pipe[O,X,A]) = Pipe.pipe(pipe, that);
    @inline def <-<[X](that: Pipe[X,I,A]) = Pipe.pipe(that, pipe);

    @inline def forever: Pipe[I,O,Nothing] = Pipe.forever(pipe);
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
