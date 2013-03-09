package conduit

import scala.collection.generic.Growable

object Util {
  import Pipe._;

  implicit class ImplicitAsPipe[O](val value: O) extends AnyVal {
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


  trait SourceLike[+O] extends Any {
    this: AnyVal =>
    def toSource: Pipe[Any,O,Unit];
  }
  trait SinkLike[-I] extends Any {
    def toSink: Pipe[I,Nothing,Unit];
  }

  implicit class IterableToSource[A](val iterable: Iterable[A]) extends AnyVal with SourceLike[A] {
    override def toSource = fromIterable(iterable);
  }
}