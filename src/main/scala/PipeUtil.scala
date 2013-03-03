import java.io._
import scala.collection.generic.Growable

object PipeUtil extends App {
  import Pipe._;

  def filter[A](p: A => Boolean): Pipe[A,A,Nothing] =
    flatMap(request, (x: A) => {
      if (p(x)) andThen(respond(x), filter(p)) else filter(p);
    });

  def readLines(r: BufferedReader): Pipe[Any,String,Unit] = {
    def loop: Pipe[Any,String,Unit] = r.readLine match {
        case null   => finish
        case l      => andThen(respond(l), loop);
      }
    delay(loop, { r.close(); });
  }

  def writeLines(w: Writer): Pipe[String,Nothing,Nothing] =
    flatMap(request[String], (x:String) => { w.write(x); w.write('\n'); writeLines(w); }).finalizer({ w.close(); });
 

  def fromIterable[A](i: Iterable[A]): Pipe[Any,A,Unit] = fromIterator(i.iterator);
  def fromIterator[A](i: Iterator[A]): Pipe[Any,A,Unit] =
    if (i.hasNext) andThen(respond(i.next()), fromIterator(i));
    else finish;

  //def toCol[A,O,C <: Growable[A]](c: C): Pipe[A,O,C] =
 

  {
    val child = Runtime.getRuntime().exec(Array(
        "/bin/sh", "-c", "find /home/p/projects/sosirecr/ -name '*java' -type f | xargs cat" ));
    val is = child.getInputStream();
    val i = readLines(new BufferedReader(new InputStreamReader(is)));

    val f = filter[String](s => s.length < 30);
    val o = writeLines(new OutputStreamWriter(System.out));
    runPipe(pipe(pipe(i, f), o));

    System.err.println("Process terminated.");
    child.waitFor();
    System.err.println("Exit status: " + child.exitValue());
    //System.exit(0);
  }
  {
    val i = fromIterable(List("abc", "efg4", "123"));
    val f = filter[String](s => s.length <= 3);
    val o = writeLines(new OutputStreamWriter(System.out));
    runPipe(pipe(pipe(i, f), o));
    System.err.println("Finished.");
    System.exit(0);
  }
}
