package conduit

import scala.collection.generic.Growable

object Util {
  import Pipe._;

  implicit def implicitAsPipe[O](value: O) = new {
    def asPipe: Pipe[Any,O,Unit] = respond[O](value)(Finalizer.empty);
  }

  def filter[A](p: A => Boolean): Pipe[A,A,Unit] = {
    import Finalizer.empty
    def loop: Pipe[A,A,Unit] =
      requestU[A,A](x => if (p(x)) (respond(x).as[A,A] >> loop) else loop);
    loop;
  }
 

  def fromSeq[A](values: A*): Pipe[Any,A,Unit] = fromIterable(values);
  def fromIterable[A](i : Iterable[A]): Pipe[Any,A,Unit]
    = fromIterator(i.iterator);
  def fromIterator[A](i : Iterator[A]): Pipe[Any,A,Unit] = {
    import Finalizer.empty
    untilF(if (i.hasNext) Some(respond[A](i.next())) else None);
  }

  def fromIterable[A]: Pipe[Iterable[A],A,Unit] =
    unfold[Iterable[A],A](i => fromIterable(i));
  def fromIterator[A]: Pipe[Iterator[A],A,Unit] =
    unfold[Iterator[A],A](i => fromIterator(i));

  //def toCol[A,O,C <: Growable[A]](c: C): Pipe[A,O,C] =


  trait SourceLike[+O] {
    def toSource: Pipe[Any,O,Unit];
  }
  trait SinkLike[-I] {
    def toSink: Pipe[I,Nothing,Unit];
  }

  implicit def iterableToSource[A](it: Iterable[A]) = new SourceLike[A] {
    override def toSource = fromIterable(it);
  }
}
