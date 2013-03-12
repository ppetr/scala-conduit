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

private[conduit] object Log {
  import java.util.logging._
  // TODO
  val logger = Logger.getLogger(Pipe.getClass().getName());

  private def log(level: Level, msg: => String, ex: Throwable = null) =
    if (logger.isLoggable(level))
      logger.log(level, msg, ex);

  def warn(msg: => String, ex: Throwable = null) =
    log(Level.WARNING, msg, ex);
  def error(msg: => String, ex: Throwable = null) =
    log(Level.SEVERE, msg, ex);
  def info(msg: => String, ex: Throwable = null) =
    log(Level.INFO, msg, ex);
}
