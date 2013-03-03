import java.io._
import scala.collection.generic.Growable

object PipeUtil extends App {
  import Pipe._;

  def filter[A,R](p: A => Boolean): Pipe[A,A,R] =
    flatMap(request[A], (x: A) => {
      if (p(x)) andThen(respond[A](x), filter(p)) else filter(p);
    });

  def readLines[I](r: BufferedReader): Pipe[I,String,Unit] = {
    def loop: Pipe[I,String,Unit] = r.readLine match {
        case null   => finish
        case l      => andThen(respond(l), loop);
      }
    delay(loop, { r.close(); });
  }

  def writeLines[O](w: Writer): Pipe[String,O,Unit] =
    flatMap(request[String], (x:String) => { w.write(x); w.write('\n'); writeLines(w); }).finalizer({ w.close(); });
 

  def fromIterable[A,I](i: Iterable[A]): Pipe[I,A,Unit] = fromIterator(i.iterator);
  def fromIterator[A,I](i: Iterator[A]): Pipe[I,A,Unit] =
    if (i.hasNext) andThen(respond(i.next()), fromIterator(i));
    else finish();

  //def toCol[A,O,C <: Growable[A]](c: C): Pipe[A,O,C] =
 

  {
    val child = Runtime.getRuntime().exec(Array(
        "/bin/sh", "-c", "find /home/p/projects/sosirecr/ -name '*java' -type f | xargs cat" ));
    val is = child.getInputStream();
    val i = readLines[Unit](new BufferedReader(new InputStreamReader(is)));

    val f = filter[String,Unit](s => s.length < 30);
    val o = writeLines[Nothing](new OutputStreamWriter(System.out));
    runPipe[Unit](pipe[Unit,String,Nothing,Unit](pipe(i, f), o));

    System.err.println("Process terminated.");
    child.waitFor();
    System.err.println("Exit status: " + child.exitValue());
    //System.exit(0);
  }
  {
    val i = fromIterable[String,Unit](List("abc", "efg4", "123"));
    val f = filter[String,Unit](s => s.length <= 3);
    val o = writeLines[Nothing](new OutputStreamWriter(System.out));
    runPipe[Unit](pipe[Unit,String,Nothing,Unit](pipe(i, f), o));
    System.err.println("Finished.");
    System.exit(0);
  }
}
