import java.io._
import scala.collection.generic.Growable

object PipeUtil extends App {
  import Pipe._;

  def filter[A,R](p: A => Boolean): Pipe[A,A,R] =
    request[A,A].flatMap(x => {
      if (p(x)) (respond[A,A](x) >> filter(p)) else filter(p);
    });

  def readLines[I](r: BufferedReader): Pipe[I,String,Unit] = {
    def loop: Pipe[I,String,Unit] = r.readLine match {
        case null   => finish[I,String,Unit]({ r.close(); })
        case l      => respond(l) >> loop;
      }
    delay(loop);
  }

  def writeLines[O](w: Writer): Pipe[String,O,Unit] =
    request[String,O].flatMap(x => { w.write(x); w.write('\n'); w.flush(); writeLines(w); });
 

  def fromIterator[A,I](i: Iterator[A]): Pipe[I,A,Unit] =
    if (i.hasNext) { respond(i.next()) >> fromIterator(i); }
    else finish();

  //def toCol[A,O,C <: Growable[A]](c: C): Pipe[A,O,C] =
 

  {
    val i = readLines[Unit](new BufferedReader(new InputStreamReader(System.in)));
    val i1 = fromIterator[String,Unit](List("abc", "efg4", "123").iterator);
    val f = filter[String,Unit](s => s.length < 30);
    val o = writeLines[Nothing](new OutputStreamWriter(System.out));
    runPipe[Unit](pipe[Unit,String,Nothing,Unit](i >-> f, o));
    //runPipe[Unit](pipe[Unit,String,Nothing,Unit](i1, o));
  }
}
