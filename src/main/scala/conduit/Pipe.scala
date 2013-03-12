/*
    This file is part of scala-conduit.

    scala-conduit is free software: you can redistribute it and/or modify it
    under the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation, either version 3 of the License, or (at your
    option) any later version.

    scala-conduit is distributed in the hope that it will be useful, but
    WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
    or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public
    License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with scala-conduit.  If not, see <http://www.gnu.org/licenses/>.
*/
package conduit

import annotation.tailrec
import collection.mutable.{ArrayBuffer, Buffer, ArrayStack, Stack, Queue}
import util.control.Exception

/**
 * Accepts input elements of type `I` and when its upstream has no
 * more output, receives its result `U`; produces output elements of
 * type `O` and when finished, returns `R`.
 *
 * In most cases, using [[conduit.Pipe]] is sufficient over this more general trait.
 *
 * All operations on `GenPipe`s are defined in the [[Pipe$ Pipe object]].
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
  def finalizer: Finalizer;
}

private final case class HaveOutput[-U,-I,+O,+R](output: O, next: () => GenPipe[U,I,O,R], override val finalizer: Finalizer)
  extends PipeCore[U,I,O,R];
private final case class NeedInput[-U,-I,+O,+R](consume: I => GenPipe[U,I,O,R], noInput: U => NoInput[U,O,R], override val finalizer: Finalizer)
  extends PipeCore[U,I,O,R];
private final case class Done[+R](result: R)
  extends PipeCore[Any,Any,Nothing,R] {
    override def finalizer = Finalizer.empty;
  }
private final case class Delay[-U,-I,+O,+R](next: () => GenPipe[U,I,O,R], override val finalizer: Finalizer)
  extends PipeCore[U,I,O,R];

// We also have two more primitives that represent two core operations on
// pipes: binding and fusing. They are converted into the above ones when a
// pipe is run.

private final case class Bind[-U,-I,+O,+R,S](first: GenPipe[U,I,O,S], cont: S => GenPipe[U,I,O,R], finalizer: Finalizer)
  extends GenPipe[U,I,O,R];
private final case class Fuse[-U,-I,X,M,+O,+R](up: GenPipe[U,I,X,M], down: GenPipe[M,X,O,R])
  extends GenPipe[U,I,O,R];
//private final case class Leftover[I,+O,+R](leftover: I, next: () => GenPipe[U,I,O,R])
//  extends GenPipe[U,I,O,R];


/**
 * Defines operations for constructing pipes as well as the standard methods
 * for running them.
 *
 * The core operations are: [[[Pipe.request request]]],
 * [[Pipe.respond respond]],
 * [[Pipe.done done]], [[Pipe.flatMap flatMap]], [[Pipe.pipe pipe]]
 * and [[Pipe.delay delay]]. All others are derived
 * from them.
 */
object Pipe
  extends Runner
{
  /*
  type LQueue[+A] = collection.immutable.Queue[A]
  val emptyLQueue: LQueue[Nothing] = collection.immutable.Queue.empty
  */

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
   * Runs the given finalizer and then returns `done(result)`.
   */
  @inline
  def doneF[R](result: R, fin: Finalizer): Source[Nothing,R] = {
    Finalizer.run(fin);
    Done(result);
  }

  /**
   * Delays a creation of a pipe. For example it can be used to defer
   * opening a file until the pipe is actually requested.
   */
  @inline
  def delay[U,I,O,R](inner: => GenPipe[U,I,O,R])(implicit finalizer: Finalizer = Finalizer.empty): GenPipe[U,I,O,R] =
    Delay(() => inner, finalizer);
  /**
   * Creates a simple pipe that processes the given action and returns the
   * its. To be composed with `flatMap` or `>>`.
   */
  @inline
  def delayVal[R](body: => R)(implicit finalizer: Finalizer = Finalizer.empty): Source[Nothing,R] =
    Delay(() => { done(body) }, finalizer);

  /**
   * Request input from upstream. Returns either the next input value or the
   * final upstream result if it has already finished.
   */
  @inline
  def request[U,I]: GenPipe[U,I,Nothing,Either[U,I]] =
    //request((i: Either[U,I]) => Done(i))(Finalizer.empty);
    request((i: I) => done(Right(i)), (u: U) => done(Left(u)));
  /**
   * Request input from upstream. Proceed either `cont` if an input value is
   * available, otherwise proceed with `end` (which receives the final upstream
   * result).
   */
  @inline
  def request[U,I,O,R](cont: I => GenPipe[U,I,O,R], end: U => NoInput[U,O,R])(implicit finalizer: Finalizer): GenPipe[U,I,O,R] =
    NeedInput(cont, end, finalizer);

  /**
   * Request input from upstream. Parameter `cont` is passed the next input value
   * (if it is available). If no input is available, the processing continues
   * with `end`. The final upstream result is ignored.
   */
 def requestI[I,O,R](cont: I => Pipe[I,O,R], end: => NoInput[Any,O,R] = done)(implicit finalizer: Finalizer): Pipe[I,O,R] =
    request[Any,I,O,R]((i: I) => cont(i), (_:Any) => end);

  /**
   * Request input from upstream. If there is input available, it is passed to
   * `cont`. If no input is availabe, the upstream final result is processed
   * with `end` and the result is returned. This is often useful when
   * constructing filter-like pipes that finish when upstream finishes.
   */
  def requestF[U,I,O,R](cont: I => GenPipe[U,I,O,R], end: U => R = const(()), runFinalizer: Boolean = true)(implicit finalizer: Finalizer): GenPipe[U,I,O,R] =
    request[U,I,O,R](cont, (u: U) => {
      val r = end(u);
      if (runFinalizer) Finalizer.run;
      done(r)
    });

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
  def untilF[U,I,O](pipe: => Option[GenPipe[U,I,O,Any]], runFinalizer: Boolean)(implicit finalizer: Finalizer): GenPipe[U,I,O,Unit] = {
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
    requestI(const(discardOutput));


  /**
   * For each input received run the pipe produced by `f`. Note that
   * these pipes cannot receive input.
   */
  def unfold[U,I,O](f: I => NoInput[Unit,O,Any])(implicit finalizer: Finalizer): GenPipe[U,I,O,U] = {
    def loop: GenPipe[U,I,O,U] =
      requestF[U,I,O,U](i => blockInput(f(i)) >> loop, (u: U) => u);
    loop;
  }
    //requestF[U,I,O,U](i => andThen(blockInput(f(i)), unfold(f)), (u: U) => u);

  /**
   * For each input received, it runs the pipe produced by `f`. Note that
   * these pipes ''can'' receive input themselves. If you know that your pipe
   * doesn't request any input, using `unfoldI` is slightly faster than
   * `unfold`.
   */
  def unfoldI[U,I,O,R](f: I => GenPipe[U,I,O,Any], end: U => R = const(()))(implicit finalizer: Finalizer): GenPipe[U,I,O,R] = {
    def loop: GenPipe[U,I,O,R] =
      requestF[U,I,O,R](i => f(i) >> loop, end);
    loop
  }

  /**
   * For each input received run the pipe produced by `f`. Note that
   * these pipes cannot receive input.
   */
  def unfoldU[I,O](f: I => NoInput[Unit,O,Any])(implicit finalizer: Finalizer): Pipe[I,O,Unit] = {
    def loop: Pipe[I,O,Unit] =
      requestI(i => blockInput(f(i)) >> loop);
    loop
  }

  /**
   * For each input received, it runs the pipe produced by `f`. Note that
   * these pipes ''can'' receive input themselves. If you know that your pipe
   * doesn't request any input, using `unfoldI` is slightly faster than
   * `unfold`.
   */
  def unfoldIU[I,O](f: I => Pipe[I,O,Any])(implicit finalizer: Finalizer): Pipe[I,O,Unit] = {
    def loop: Pipe[I,O,Unit] =
      requestI(i => f(i) >> loop);
    loop
  }



  @inline
  def pipe[U,I,X,M,O,R](i: GenPipe[U,I,X,M], o: GenPipe[M,X,O,R]): GenPipe[U,I,O,R] =
    Fuse(i, o);


  def idP[A]: Pipe[A,A,Unit] = {
    implicit val fin = Finalizer.empty;
    def loop: Pipe[A,A,Unit] =
      requestI(x => respond(x, loop));
    loop
  }

  /**
   * Processes input with a given function and passes it to output.
   * Runs the finalizer at the end.
   */
  def mapF[U,I,O](f: I => O, runFinalizer: Boolean = true)(implicit finalizer: Finalizer): GenPipe[U,I,O,U] = {
    def loop: GenPipe[U,I,O,U] =
      requestF(x => respond(f(x), loop), u => u, runFinalizer)
    loop
  }

  /**
   * Folds the input using a given function. Produces no output.
   * Runs the finalizer at the end.
   */
  def foldF[I,R](f: (R, I) => R, start: R, runFinalizer: Boolean = true)(implicit finalizer: Finalizer): Sink[I,R] = {
    def loop(r: R): Sink[I,R] =
      requestF(x => loop(f(r, x)), (_) => r, runFinalizer)
    loop(start)
  }

  /**
   * Processes all input with a given function.
   * Runs the finalizer at the end.
   */
   def sinkF[U,I,R](f: I => Unit, end: U => R = const(()), runFinalizer: Boolean = true)(implicit finalizer: Finalizer): GenPipe[U,I,Nothing,R] = {
     def loop: GenPipe[U,I,Nothing,R] =
       requestF(x => { f(x); loop }, end, runFinalizer)
     loop
   }


  /*
  sealed trait Leftover[+I,+R] { val result: R; }
  final case class HasLeft[+I,+R](left: I, override val result: R)
    extends Leftover[I,R];
  final case class NoLeft[+R](override val result: R)
    extends Leftover[Nothing,R];
  */


  override def runPipe[U,O,R](pipe: NoInput[U,O,R], init: U, sender: O => Unit): R = {
    import Finalizer._
    @tailrec
    def step[R](pipe: NoInput[U,O,R]): R = {
      stepPipe[U,Nothing,O,R](pipe) match {
        case Done(r)                  => r;
        case HaveOutput(o, next, fin) => step(fin.protect({ sender(o); next() }));
        case NeedInput(_, end, fin)   => step(fin.protect({ end(init) }));
        case Delay(next, fin)         => step(fin.protect({ next() }));
      }
    }
    step(pipe);
  }

  override def runPipeE[U,I,O,R](pipe: GenPipe[U,I,O,R], receiver: () => Either[U,I], sender: O => Unit): R = {
    import Finalizer._
    @tailrec
    def step[R](pipe: GenPipe[U,I,O,R]): R = {
      stepPipe(pipe) match {
        case Done(r)                  => r;
        case HaveOutput(o, next, fin) => step(fin.protect({ sender(o); next() }));
        case NeedInput(cons, end, fin) =>
          fin.protect({ receiver() match {
              case Left(u)   => Left((u, end(u)))
              case Right(i)  => Right(cons(i))
            }}) match {
            case Left((u, next))  => runPipe(next, u, sender);
            case Right(next)      => step(next);
          }
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

  private def stepBind[U,I,O,R,S](startPipe: GenPipe[U,I,O,S], cont: S => GenPipe[U,I,O,R], fin: Finalizer): PipeCore[U,I,O,R] = {
    def loop(pipe: GenPipe[U,I,O,S]): PipeCore[U,I,O,R] =
      stepPipe(pipe) match {
        case Done(s)                    => Delay(() => cont(s), fin)
        case NeedInput(cons, end, finI) => NeedInput(i  => loop(cons(i)),
                                                     u  => loop(stepNoInput(u, end(u))),
                                                     finI)
        case HaveOutput(o, next, finO)  => HaveOutput(o, () => loop(next()), finO)
        case Delay(pipe, finD)          => Delay(() => loop(pipe()), finD)
      }
    loop(startPipe);
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
            case HaveOutput(x, next, _)
                          => Delay(() => step(next(), consO(x), finUp), finPlus);
            case NeedInput(consI, endI, _)
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


  /**
   * Declares a set of operations that can be performed on a pipe.
   * In particular `flatMap` and all operations derived from it, and pipe composition.
   */
  trait Monadic[U,I,O,R] extends Any {
    def pipe: GenPipe[U,I,O,R];

    /**
     * When this pipe finishes, pass the result to `f` to continue the computation.
     */
    def flatMap[U2 <: U,I2 <: I,O2 >: O,B](f: R => GenPipe[U2,I2,O2,B])(implicit finalizer: Finalizer): GenPipe[U2,I2,O2,B];
    /**
     * A synonym for [[flatMap]].
     */
    @inline
    final def >>=[U2 <: U,I2 <: I,O2 >: O,B](f: R => GenPipe[U2,I2,O2,B])(implicit finalizer: Finalizer): GenPipe[U2,I2,O2,B] =
      flatMap(f);
    /**
     * Map the final value of this pipe.
     */
    def map[B](f: R => B)(implicit finalizer: Finalizer): GenPipe[U,I,O,B] =
      flatMap((r: R) => done(f(r)));
    /**
     * Sequence this pipe with another one. When this pipe finishes, `p`
     * continues (regardless of the result of this).
     */
    def >>[U2 <: U,I2 <: I,O2 >: O,B](p: => GenPipe[U2,I2,O2,B])(implicit finalizer: Finalizer): GenPipe[U2,I2,O2,B] =
      flatMap(const(p));

    /**
     * Prepends `p` to this pipe. A reverse of `>>`.
     */
    def <<[U2 <: U,I2 <: I,O2 >: O](p: GenPipe[U2,I2,O2,Any])(implicit finalizer: Finalizer): GenPipe[U2,I2,O2,R] =
      p >> pipe;

    /**
     * Feeds the output and the result of this pipe into `that`.
     */
    def >->[X,S](that: GenPipe[R,O,X,S]): GenPipe[U,I,X,S];
    /**
     * Feeds the output and the result of `that` into this pipe.
     * The reverse of `>->`.
     */
    def <-<[X,M](that: GenPipe[M,X,I,U]): GenPipe[M,X,O,R] =
      that >-> pipe;
  }

  protected trait MonadicImpl[U,I,O,R] extends Any with Monadic[U,I,O,R] {
    @inline def flatMap[U2 <: U,I2 <: I,O2 >: O,B](f: R => GenPipe[U2,I2,O2,B])(implicit finalizer: Finalizer) = Pipe.flatMap(pipe, f)

    @inline def >->[X,S](that: GenPipe[R,O,X,S]) = Pipe.pipe(pipe, that);
  }

  /**
   * An implicit class that adds [[Pipe.Monadic monadic operations]] to [[Pipe]].
   */
  implicit class FlatMap[U,I,O,R](val pipe: GenPipe[U,I,O,R])
    extends AnyVal with MonadicImpl[U,I,O,R];
  /**
   * An implicit class that adds [[Pipe.Monadic monadic operations]] to pipes
   * with no results (that is to infinite pipes that never return).
   */
  implicit class FlatMapResultNothing[U,I,O](val pipe: GenPipe[U,I,O,Nothing])
    extends AnyVal with MonadicImpl[U,I,O,Nothing];
  /**
   * An implicit class that adds [[Pipe.Monadic monadic operations]] to pipes
   * with no output (sinks).
   */
  implicit class FlatMapOutputNothing[U,I,R](val pipe: GenPipe[U,I,Nothing,R])
    extends AnyVal with MonadicImpl[U,I,Nothing,R];
}
