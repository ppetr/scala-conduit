import java.io._
import scala.collection.generic.Growable

object PipeUtil {
  import Pipe._;

  implicit def closeFin(implicit c: Closeable): Finalizer =
    Finalizer { System.err.println("Closing " + c); c.close() }

  implicit def implicitAsPipe[O](value: O) = new {
    def asPipe: Pipe[Any,O,Unit] = respond[O](value)(Finalizer.empty);
  }

  def filter[A](p: A => Boolean): Pipe[A,A,Nothing] = {
    import Finalizer.empty
    def loop: Pipe[A,A,Nothing] =
      request[A].asO[A].flatMap(x => if (p(x)) (respond(x).as[A,A] >> loop) else loop);
    loop;
  }
  /*
  (for(x <- request[A].asO[A];
      _ <- if (p(x)) respond(x).asI[A] else finish.as[A,A]
    ) yield finish.as[A,A]).forever;
  */
  /*
    flatMap(request, (x: A) => {
      if (p(x)) (respond(x) >> filter(p)) else filter(p);
    });
  */

  lazy val readLinesFile: Pipe[File,String,Nothing] = {
    import Finalizer.empty
    pipe(mapP((f: File) => new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))), readLines);
  }
    //arrP((f: File) => new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) >-> readLines;
  lazy val readLines: Pipe[BufferedReader,String,Nothing] =
    unfold[BufferedReader,String,Unit](readLines _);

  def readLines(r: BufferedReader): Pipe[Any,String,Unit] = {
    implicit val fin = closeFin(r);
    until[Any,String](Option(r.readLine).map(respond[String] _));
  }

  def writeLines(w: Writer): Pipe[String,Nothing,Nothing] = {
    implicit val fin = closeFin(w)
    forever(request((x: String) => { w.write(x); w.write('\n'); w.flush(); finish; }));
  }
 

  def fromSeq[A](values: A*): Pipe[Any,A,Unit] = fromIterable(values);
  def fromIterable[A](i : Iterable[A]): Pipe[Any,A,Unit]
    = fromIterator(i.iterator);
  def fromIterator[A](i : Iterator[A]): Pipe[Any,A,Unit] = {
    import Finalizer.empty
    until(if (i.hasNext) Some(respond[A](i.next())) else None);
  }

  def fromIterable[A]: Pipe[Iterable[A],A,Any] =
    unfold[Iterable[A],A,Unit](i => fromIterable(i));
  def fromIterator[A]: Pipe[Iterator[A],A,Any] =
    unfold[Iterator[A],A,Unit](i => fromIterator(i));

  //def toCol[A,O,C <: Growable[A]](c: C): Pipe[A,O,C] =


  trait SourceLike[+O] {
    def toSource: Pipe[Any,O,Unit];
  }
  trait SinkLike[-I] {
    def toSink: Pipe[I,Nothing,Nothing];
  }

  implicit def iterableToSource[A](it: Iterable[A]) = new SourceLike[A] {
    override def toSource = fromIterable(it);
  }
  implicit def bufferedReaderToSource(r: BufferedReader) = new SourceLike[String] {
    override def toSource = readLines(r);
  }
 

  // -----------------------------------------------------------------
  def main(argv: Array[String]) {
    import Finalizer.empty
    try {
      if (true)
      {
        val child = Runtime.getRuntime().exec(Array(
            "/bin/sh", "-c", "find /home/p/projects/sosirecr/ -name '*java' -type f | xargs cat" ));
        val is = child.getInputStream();
        //val i = readLines(new BufferedReader(new InputStreamReader(is)));
        val i = new BufferedReader(new InputStreamReader(is)).asPipe >-> readLines;

        val f = filter[String](s => s.length < 30);
        val o = writeLines(new OutputStreamWriter(System.out));
        runPipe(i >-> f >-> o);

        System.err.println("Process terminated.");
        child.waitFor();
        System.err.println("Exit status: " + child.exitValue());
        //System.exit(0);
      } else
      {
        val i = List("abc", "efg4", "123").toSource;
        val f = filter[String](s => s.length <= 3);
        val o = writeLines(new OutputStreamWriter(System.out));
        runPipe(i >-> f >-> o);
        System.err.println("Finished.");
        System.exit(0);
      }
    } catch {
      case (ex: Exception) => ex.printStackTrace;
    }
  }
}
