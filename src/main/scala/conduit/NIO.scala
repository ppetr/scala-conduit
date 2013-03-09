package conduit

import java.io._
import java.nio._
import java.nio.channels._

object NIO {
  import Pipe._;
  import IO._;
  import Util._;

  def readChannel(buf: ByteBuffer, c: ReadableByteChannel): Pipe[Any,ByteBuffer,Unit] = {
    implicit val fin = Finalizer({ c.close() });
    untilF[Any,ByteBuffer]({
        buf.clear();
        val i = c.read(buf);
        if (i < 0) None;
        else Some(respond(buf));
      });
  }

  def readFile(file: File, buf: ByteBuffer): Pipe[Any,ByteBuffer,Unit] =
    delay {
      implicit val is = new FileInputStream(file);
      readChannel(buf, is.getChannel());
    };

  def readFiles(buf: ByteBuffer): Pipe[File,ByteBuffer,Unit] = {
    import Finalizer.empty
    unfold[File,ByteBuffer](f => readFile(f, buf));
  }

  def writeToOutputStream(os: OutputStream): Pipe[Array[Byte],Nothing,Unit] = {
    def loop(): Pipe[Array[Byte],Nothing,Unit] =
      requestU((buf: Array[Byte]) => { os.write(buf); loop() })(closeFin(os));
    loop();
  }

  def writeChannel(c: WritableByteChannel): Pipe[ByteBuffer,Nothing,Unit] = {
    def loop(): Pipe[ByteBuffer,Nothing,Unit] =
      requestU((buf: ByteBuffer) => { c.write(buf); loop(); })(closeFin(c));
    loop();
  }

  def writeFile(file: File): Pipe[ByteBuffer,Nothing,Any] =
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
    def loop(): Pipe[B,B,Unit] =
      requestU[B,B](b => untilF[B,B] { if (b.hasRemaining()) Some(respond(b)) else None } >> loop());
    loop();
  }

  def list(dir: File): Pipe[Any,File,Unit] =
    Util.fromIterable(
      dir.listFiles(new FileFilter { def accept(f: File) = f.isFile; })
    );

  def listRec(dir: File): Pipe[Any,File,Unit] =
  {
    import Util._;
    import Finalizer.empty
    val all = dir.listFiles();
    val files = all.toIterator.filter(_.isFile());
    val dirs  = all.toIterator.filter(_.isDirectory());
    fromIterator(files) >>
      (fromIterator(dirs) >-> unfold(listRec _));
  }


  // -----------------------------------------------------------------

  def main(argv: Array[String]) =
  {
    import Util._;
    import Finalizer.empty

    val log: Pipe[String,Nothing,Unit] =
      writeLines(new OutputStreamWriter(System.out));

    val pipe =
      listRec(new File(".")) >->
        filter[File](_.getName().endsWith(".scala")) >->
        readLinesFile >->
        log;

    runPipe(pipe);
  }
}
