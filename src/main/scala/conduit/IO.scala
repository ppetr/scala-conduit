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

  /**
   * Implicit method for creating finalizers that close a `Closeable`.
   */
  implicit def closeFin(implicit c: Closeable): Finalizer =
    Finalizer { System.err.println("Closing " + c); c.close() }


  /**
   * For each file on input output its content as lines.
   */
  lazy val readLinesFile: Pipe[File,String,Unit] = {
    implicit val fin = Finalizer.empty
    mapF((f: File) => new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) >-> readLines
  }


  /**
   * For each `BufferedReader` on input output its content as lines.
   */
  lazy val readLines: Pipe[BufferedReader,String,Unit] =
    unfoldI(readLines _)(Finalizer.empty);

  /**
   * Output the content of a `BufferedReader` as lines.
   */
  def readLines(r: BufferedReader): Source[String,Unit] = {
    implicit val fin = closeFin(r);
    untilF[Any,Any,String](Option(r.readLine).map(respond[String] _));
  }

  implicit class BufferedReaderToSource(val reader: BufferedReader)
    extends AnyVal with SourceLike[String]
  {
    override def toSource = readLines(reader);
  }


  /**
   * Write input strings to a given `Writer`.
   */
  def writeLines(w: Writer): Sink[String,Unit] = {
    implicit val fin = closeFin(w) ++
      { System.err.println("Input finished."); };
    sinkF((x: String) => { w.write(x); w.write('\n'); w.flush(); })
  }



  /**
   * List files in a given directory.
   */
  def list(dir: File): Source[File,Unit] =
    Util.fromIterable(
      dir.listFiles(new FileFilter { def accept(f: File) = f.isFile; })
    );

  /**
   * Recursively list files under a given directory. First output immediate
   * descendants and then their content recursively.
   *
   * Implemented using [[Pipe.feedback]].
   */
  def listRec: Pipe[File,File,Unit] = {
    import Util._;
    implicit val fin = Finalizer.empty
    def f: Pipe[File,Either[File,File],Unit] =
      mapF((f: File) => {
        Option(f.listFiles).map(_.toIterable) getOrElse Iterable.empty
      }) >->
        fromIterable >->
        unfoldI(f => if (f.isFile()) respond(Right(f))
                     else if (f.isDirectory()) respond(Left(f))
                     else done)
      /*
      val all = dir.listFiles();
      val files = all.toIterator.filter(_.isFile());
      val dirs  = all.toIterator.filter(_.isDirectory());
      fromIterator(files) >>:
        (fromIterator(dirs) >-> unfoldI(listRec _));
        */
    feedback(f)
  }
}
