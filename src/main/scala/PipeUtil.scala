import java.io._
import scala.collection.generic.Growable

object PipeUtil extends App {
  import Pipe._;

  def close[I,O,R](c: Closeable, pipe: => Pipe[I,O,R]): Pipe[I,O,R]
    = delay(pipe, { c.close(); System.err.println(">>> closed " + c); System.err.flush(); });

  def filter[A](p: A => Boolean): Pipe[A,A,Nothing] = {
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

  lazy val readLines: Pipe[File,String,Unit] =
    unfold[File,String,Unit](f =>
      readLines(new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8")))
    );

  def readLines(r: BufferedReader): Pipe[Any,String,Unit] =
    close(r, until[Any,String](Option(r.readLine).map(respond[String] _)));

  def writeLines(w: Writer): Pipe[String,Nothing,Nothing] =
    close(w, request[String].map((x:String) => { w.write(x); w.write('\n'); w.flush(); })
      .forever);
 

  def fromSeq[A](values: A*): Pipe[Any,A,Unit] = fromIterable(values);
  def fromIterable[A](i : Iterable[A]): Pipe[Any,A,Unit]
    = fromIterator(i.iterator);
  def fromIterator[A](i : Iterator[A]): Pipe[Any,A,Unit] =
    until(if (i.hasNext) Some(respond[A](i.next())) else None);

  def fromIterable[A]: Pipe[Iterable[A],A,Any] =
    unfold[Iterable[A],A,Unit](i => fromIterable(i));
  def fromIterator[A]: Pipe[Iterator[A],A,Any] =
    unfold[Iterator[A],A,Unit](i => fromIterator(i));

  //def toCol[A,O,C <: Growable[A]](c: C): Pipe[A,O,C] =
 

  {
    val child = Runtime.getRuntime().exec(Array(
        "/bin/sh", "-c", "find /home/p/projects/sosirecr/ -name '*java' -type f | xargs cat" ));
    val is = child.getInputStream();
    val i = readLines(new BufferedReader(new InputStreamReader(is)));

    val f = filter[String](s => s.length < 30);
    val o = writeLines(new OutputStreamWriter(System.out));
    runPipe(i >-> f >-> o);

    System.err.println("Process terminated.");
    child.waitFor();
    System.err.println("Exit status: " + child.exitValue());
    //System.exit(0);
  }
  {
    val i = fromIterable(List("abc", "efg4", "123"));
    val f = filter[String](s => s.length <= 3);
    val o = writeLines(new OutputStreamWriter(System.err));
    runPipe(i >-> f >-> o);
    System.err.println("Finished.");
    System.exit(0);
  }
}
