import java.io._
import scala.collection.generic.Growable

object PipeUtil extends App {
  import Pipe._;

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

  def readLines(r: BufferedReader): Pipe[Any,String,Unit] =
    until[Any,String](Option(r.readLine).map(respond[String] _));

  def writeLines(w: Writer): Pipe[String,Nothing,Nothing] =
    request[String].map((x:String) => { w.write(x); w.write('\n'); })
      .forever.finalizer({ w.close(); println("Closed output"); });
 

  def fromIterable[A](i: Iterable[A]): Pipe[Any,A,Unit] = fromIterator(i.iterator);
  def fromIterator[A](i: Iterator[A]): Pipe[Any,A,Unit] =
    until(if (i.hasNext) Some(respond[A](i.next())) else None);

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
