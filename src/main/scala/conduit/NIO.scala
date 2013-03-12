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
import java.nio._
import java.nio.channels._

object NIO {
  import Pipe._;
  import IO._;
  import Util._;

  def readChannel(buf: ByteBuffer, c: ReadableByteChannel): Source[ByteBuffer,Unit] = {
    implicit val fin = Finalizer({ c.close() });
    untilF[Any,Any,ByteBuffer]({
        buf.clear();
        val i = c.read(buf);
        if (i < 0) None;
        else Some(respond(buf));
      });
  }

  def readFile(file: File, buf: ByteBuffer): Source[ByteBuffer,Unit] =
    delay {
      implicit val is = new FileInputStream(file);
      readChannel(buf, is.getChannel());
    };

  def readFiles(buf: ByteBuffer): Pipe[File,ByteBuffer,Unit] = {
    import Finalizer.empty
    unfoldI(f => readFile(f, buf));
  }

  def writeToOutputStream(os: OutputStream): Pipe[Array[Byte],Nothing,Unit] =
    sinkF(os.write(_:Array[Byte]))(closeFin(os))

  def writeChannel(c: WritableByteChannel): Sink[ByteBuffer,Unit] =
    sinkF(c.write(_:ByteBuffer))(closeFin(c));

  def writeFile(file: File): Sink[ByteBuffer,Any] =
    delay {
      implicit val os = new FileOutputStream(file);
      writeChannel(os.getChannel());
    }

  /**
   * Ensures that each buffer is fully consumed by downstream
   * (<code>hasRemaining()</code> returns <code>false</code>) before requesting
   * upstream a new buffer.
   */
  def leftovers[B <: Buffer]: Pipe[B,B,Unit] = {
    import Finalizer.empty;
    unfoldI((b: B) => untilF[Any,B,B] {
          if (b.hasRemaining()) Some(respond(b)) else None
        });
  }


  // -----------------------------------------------------------------

  def main(argv: Array[String]) =
  {
    import Util._;
    import Finalizer.empty

    val log: Sink[String,Unit] =
      writeLines(new OutputStreamWriter(System.out));

    val pipe =
      listRec(new File(".")) >->
        filter[File,Unit](_.getName().endsWith(".scala")) >->
        readLinesFile >->
        log;

    runPipe(pipe);
  }
}
