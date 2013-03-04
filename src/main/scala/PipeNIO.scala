import java.io._
import java.nio._
import java.nio.channels._

object PipeNIO {
  import Pipe._;
  import PipeUtil._;

  def readChannel(buf: ByteBuffer, c: ReadableByteChannel): Pipe[Any,ByteBuffer,Unit] =
      close(c, until[Any,ByteBuffer]({
          buf.clear();
          val i = c.read(buf);
          if (i < 0) None;
          else Some(respond(buf));
        }));

  def readFile(file: File, buf: ByteBuffer): Pipe[Any,ByteBuffer,Unit] =
    delay {
      val is = new FileInputStream(file);
      close(is, readChannel(buf, is.getChannel()));
    };

  def readFiles(buf: ByteBuffer): Pipe[File,ByteBuffer,Unit] =
    unfold[File,ByteBuffer,Unit](f => readFile(f, buf));

  def writeToOutputStream(os: OutputStream): Pipe[Array[Byte],Nothing,Any] =
    forever(request((buf: Array[Byte]) => finish(os.write(buf))));

  def writeChannel(c: WritableByteChannel): Pipe[ByteBuffer,Nothing,Any] =
      close(c, forever(request((buf: ByteBuffer) => finish(c.write(buf)))));

  def writeFile(file: File): Pipe[ByteBuffer,Nothing,Any] =
    delay {
      val os = new FileOutputStream(file);
      close(os, writeChannel(os.getChannel()));
    }

  /**
   * Ensures that each buffer is fully consumed by downstream
   * (<code>hasRemaining()</code> returns <code>false</code>) before requesting
   * upstream a new buffer.
   */
  def leftovers[B <: Buffer]: Pipe[B,B,Nothing] =
    request((b: B) => until { if (b.hasRemaining()) Some(respond(b)) else None }).forever;

  def list(dir: File): Pipe[Any,File,Unit] =
    PipeUtil.fromIterable(
      dir.listFiles(new FileFilter { def accept(f: File) = f.isFile; })
    );

  def listRec(dir: File): Pipe[Any,File,Unit] =
  {
    import PipeUtil._;
    val all = dir.listFiles();
    val files = all.toIterator.filter(_.isFile());
    val dirs  = all.toIterator.filter(_.isDirectory());
    fromIterator(files) >>
      (fromIterator(dirs) >-> unfold(listRec _));
  }


  // -----------------------------------------------------------------

  def main(argv: Array[String]) =
  {
    import PipeUtil._;

    val log: Pipe[String,Nothing,Nothing] =
      writeLines(new OutputStreamWriter(System.out));

    val pipe =
      listRec(new File("/home/p/projects/sosirecr/")) >->
        filter(_.getName().endsWith(".java")) >->
        readLines >->
        log;

    runPipe(pipe);
  }
}
