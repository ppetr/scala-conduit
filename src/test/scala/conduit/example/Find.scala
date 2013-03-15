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
package conduit.example

import conduit._
import conduit.Pipe._
import conduit.Util._
import conduit.IO._

import java.io._

/**
 * Searches the current directory for _*.scala_ files
 * and print their content to stdout.
 *
 * Run with `sbt test:run`.
 *
 * '''Run at your own risk!'''
 */
object Find {
  def main(argv: Array[String]) =
  {
    import Util._;
    import Finalizer.empty

    Confirm("WARNING: This command searches all *.scala files in the current directory\nand prints their content to stdout.\nRUN AT YOUR OWN RISK!");

    val log: Sink[String,Unit] =
      writeLines(new OutputStreamWriter(System.out));

    val pipe =
      new File(".").asPipe >->
        listRec >->
        filter[File,Unit](_.getName().endsWith(".scala")) >->
        readLinesFile >->
        log;

    runPipe(pipe);
  }
}
