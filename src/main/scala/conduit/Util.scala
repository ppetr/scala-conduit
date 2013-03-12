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

  implicit class ImplicitAsPipe[O](val value: O) extends AnyVal {
    def asPipe: Source[O,Unit] = respond[O](value)(Finalizer.empty);
  }

  def filter[A,R](p: A => Boolean): GenPipe[R,A,A,R] = {
    import Finalizer.empty
    //unfold[R,A,A](x => if (p(x)) respond(x) else done)
    unfoldI[R,A,A,R](x => if (p(x)) respond(x) else done, identity _)
  }
 

  def fromSeq[A](values: A*): Source[A,Unit] = fromIterable(values);
  def fromIterable[A](i : Iterable[A]): Source[A,Unit]
    = fromIterator(i.iterator);
  def fromIterator[A](i : Iterator[A]): Source[A,Unit] = {
    import Finalizer.empty
    untilF(if (i.hasNext) Some(respond[A](i.next())) else None);
  }

  def fromIterable[A]: Pipe[Iterable[A],A,Unit] =
    unfoldU[Iterable[A],A](i => fromIterable(i));
  def fromIterator[A]: Pipe[Iterator[A],A,Unit] =
    unfoldU[Iterator[A],A](i => fromIterator(i));

  //def toCol[A,O,C <: Growable[A]](c: C): Pipe[A,O,C] =


  trait SourceLike[+O] extends Any {
    this: AnyVal =>
    def toSource: Source[O,Unit];
  }
  trait SinkLike[-I] extends Any {
    def toSink: Sink[I,Unit];
  }

  implicit class IterableToSource[A](val iterable: Iterable[A]) extends AnyVal with SourceLike[A] {
    override def toSource = fromIterable(iterable);
  }


  def main(argv: Array[String]) {
    import Finalizer.empty

    println(
      runPipe(
        fromIterable(1 until 10000000) >->
        foldF[Int,Int](_ + _, 0)
      )
    );
  }
}
