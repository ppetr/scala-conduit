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

import java.io._

object IO {
  import Pipe._;
  import Util._;

  implicit def closeFin(implicit c: Closeable): Finalizer =
    Finalizer { System.err.println("Closing " + c); c.close() }

  lazy val readLinesFile: Pipe[File,String,Unit] = {
    import Finalizer.empty
    mapP((f: File) => new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) >-> readLines
  }
    //arrP((f: File) => new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) >-> readLines;

  lazy val readLines: Pipe[BufferedReader,String,Unit] =
    unfoldIU[BufferedReader,String](readLines _);

  def readLines(r: BufferedReader): Source[String,Unit] = {
    implicit val fin = closeFin(r);
    untilF[Any,Any,String](Option(r.readLine).map(respond[String] _));
  }

  implicit class BufferedReaderToSource(val reader: BufferedReader)
    extends AnyVal with SourceLike[String]
  {
    override def toSource = readLines(reader);
  }


  def writeLines(w: Writer): Sink[String,Unit] = {
    implicit val fin = closeFin(w);
    def read(x: String) =
      { w.write(x); w.write('\n'); w.flush(); loop() }
    def loop(): Sink[String,Unit] =
      requestE(read _, { System.err.println("Input finished."); Finalizer.run(fin) })
    loop();
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

        val f = filter[String,Unit](s => s.length < 30);
        val o = writeLines(new OutputStreamWriter(System.out));
        runPipe(i >-> f >-> o);

        System.err.println("Process terminated.");
        child.waitFor();
        System.err.println("Exit status: " + child.exitValue());
        //System.exit(0);
      } else
      {
        val i = List("abc", "efg4", "123").toSource;
        val f = filter[String,Unit](s => s.length <= 3);
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
