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

/**
 * Represents a set of actions to run when an operation fails for some
 * reason. The actions usually release any resources held during the
 * processing of a pipeline.
 *
 * If the operation fails because of an exception, `Finalizer` receives it as
 * an argument. This allows it to act accordingly, for example to distinguish
 * between recoverable exceptions (such as
 * `NullPointerException) and unrecoverable ones (such as
 * `OutOfMemoryError`). Note that such an exception can
 * occur anywhere in a pipeline, not just in the pipe guarded by a finalizer.
 * Therefore `Finalizer`s must not make any assumptions about what exception
 * it will receive.
 */
class Finalizer protected (protected[conduit] val actions: Seq[Option[Throwable] => Unit]) {
  def isEmpty = actions.isEmpty;

  protected def run(th: Option[Throwable]): Unit =
    actions.foreach(Finalizer.runQuietly(_, th));

  /**
   * If an exception occurs while running <var>body</var>, run this finalizer.
   */
  def protect[R](body: => R): R =
    if (isEmpty) body
    else
      try { body }
      catch {
        case (ex: Exception) => { run(Some(ex)); throw ex; }
        case (ex: Throwable) => {
          Log.error("Serious Error! Finalizers may fail.", ex);
          run(Some(ex));
          throw ex;
        }
      }

  /**
   * Join two finalizers into one.
   */
  def ++(that: Finalizer): Finalizer =
    new Finalizer(this.actions ++ that.actions);
  /**
   * Adds a block of code to this finalizer.
   */
  def ++(body: => Unit): Finalizer =
    this ++ Finalizer(body)
}


object Finalizer {
  /**
   * The empty finalizer that doesn't run any action.
   */
  implicit val empty: Finalizer = new Finalizer(Seq.empty);

  /**
   * Create a finalizer from a block of code.
   */
  def apply(fin: => Unit): Finalizer = new Finalizer(Seq((_) => fin));
  /**
   * Create a finalizer from a function that can examine what exception occurred.
   */
  def apply(fin: Option[Throwable] => Unit): Finalizer = new Finalizer(Seq(fin));

  protected def runQuietly(f: Option[Throwable] => Unit, th: Option[Throwable] = None) =
    try { f(th) }
    catch {
      case (ex: Exception) => Log.warn("Exception in a finalizer", ex);
      case (ex: Throwable) => Log.error("Error in a finalizer", ex);
    }
  /**
   * Run the action of a finalizer. The finalizer will not receive any exception.
   */
  def run(implicit fin: Finalizer): Unit =
    fin.run(None);
}
