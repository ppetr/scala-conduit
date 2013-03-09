package conduit

import scala.collection.generic.Growable

object Util {
  import Pipe._;

  implicit class ImplicitAsPipe[O](val value: O) extends AnyVal {
    def asPipe: Source[O,Unit] = respond[O](value)(Finalizer.empty);
  }

  def filter[A,R](p: A => Boolean): GenPipe[R,A,A,R] = {
    import Finalizer.empty
    def loop: GenPipe[R,A,A,R] =
      requestI[R,A,A](x => if (p(x)) (respond(x).as[R,A] >> loop) else loop);
    loop;
  }
 

  def fromSeq[A](values: A*): Source[A,Unit] = fromIterable(values);
  def fromIterable[A](i : Iterable[A]): Source[A,Unit]
    = fromIterator(i.iterator);
  def fromIterator[A](i : Iterator[A]): Source[A,Unit] = {
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
    def toSource: Source[O,Unit];
  }
  trait SinkLike[-I] extends Any {
    def toSink: Sink[I,Unit];
  }

  implicit class IterableToSource[A](val iterable: Iterable[A]) extends AnyVal with SourceLike[A] {
    override def toSource = fromIterable(iterable);
  }
}
