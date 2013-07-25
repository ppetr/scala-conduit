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

import scala.collection.generic.Growable

object Util {
  import Pipe._;

  /**
   * Implicit class for converting values into pipes that output just that
   * singleton value.
   */
  implicit class ImplicitAsPipe[O](val value: O) extends AnyVal {
    def asPipe: Source[O,Unit] = respond[O](value)(Finalizer.empty);
  }


  /**
   * Filter input using a given predicate. Pass the upstream result as the
   * downstream result unmodified.
   */
  @inline
  def filter[A,R](p: A => Boolean): GenPipe[R,A,A,R] =
    filter[R,A,R](p, identity _);

  /**
   * Filter input using a given predicate. Modify the upstream result with the
   * given function.
   */
  def filter[U,A,R](p: A => Boolean, end: U => R): GenPipe[U,A,A,R] = {
    implicit val fin = Finalizer.empty
    unfoldI[U,A,A,R](x => if (p(x)) respond(x) else done, end)
  }

  /**
   * Resend input to output until the given predicate is satisfied.
   */
  def takeWhile[A,R](p: A => Boolean): Pipe[A,A,Unit] = {
    implicit val fin = Finalizer.empty
    def loop: Pipe[A,A,Unit] =
      requestI[A,A,Unit]((x: A) =>
        if (p(x)) respond(x, loop) else done
      );
    loop
  }
 

  /**
   * Create a source pipe from a sequence.
   */
  def fromSeq[A](values: A*): Source[A,Unit] = fromIterable(values);
  /**
   * Create a source pipe from an iterable.
   */
  def fromIterable[A](i : Iterable[A]): Source[A,Unit]
    = fromIterator(i.iterator);
  /**
   * Create a source pipe from an iterator.
   */
  def fromIterator[A](i : Iterator[A]): Source[A,Unit] = {
    implicit val fin = Finalizer.empty
    untilF(if (i.hasNext) Some(respond[A](i.next())) else None);
  }

  /**
   * Create a source pipe that reads iterables on input and outputs their
   * content.
   */
  def fromIterable[A]: Pipe[Iterable[A],A,Unit] =
    unfold((i: Iterable[A]) => fromIterable(i))(Finalizer.empty);
  /**
   * Create a source pipe that reads iterators on input and outputs their
   * content.
   */
  def fromIterator[A]: Pipe[Iterator[A],A,Unit] =
    unfold((i: Iterator[A]) => fromIterator(i))(Finalizer.empty);


  def toCol[A,C](implicit cbf: scala.collection.generic.CanBuildFrom[Nothing,A,C]): Sink[A,C] =
    delay {
      val b = cbf();
      sinkF[Any,A,C](b += _, (_) => b.result())(Finalizer.empty);
    }


  def chkInterrupted[U,I]: GenPipe[U,I,I,U] =
    mapF[U,I,I,U](x => {
      if (Thread.interrupted())
        throw new InterruptedException;
      x
    }, identity _)(Finalizer.empty)

  /**
   * A trait for structures that can be converted to source pipes.
   */
  trait SourceLike[+O] extends Any {
    this: AnyVal =>
    def toSource: Source[O,Unit];
  }
  /**
   * A trait for structures that can be converted to sink pipes.
   */
  trait SinkLike[-I] extends Any {
    def toSink: Sink[I,Unit];
  }

  /**
   * Implicit class that enriches any iterable with `SourceLike.toSource`.
   */
  implicit class IterableToSource[A](val iterable: Iterable[A]) extends AnyVal with SourceLike[A] {
    override def toSource = fromIterable(iterable);
  }
}
