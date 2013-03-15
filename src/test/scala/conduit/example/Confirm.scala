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
 * A helper object for confirming a potentially unwanted actions.
 */
protected object Confirm {
  def apply(msg: String, args: Object*) {
    import java.io.Console

    val c = System.console()
    if (c == null)
      throw new RuntimeException("No console available, exiting!")
    c.format(msg, args : _*);
    c.format("\n\nPress Ctrl-C to abort now. Press Enter to proceed.\n");
    c.readLine();
  }
}
