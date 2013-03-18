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
package conduit.test

import org.scalatest._
import org.scalatest.PropSpec
import org.scalatest.prop.PropertyChecks
import org.scalatest.matchers.ShouldMatchers

/**
 * A helper testing class that records a timestamp each time its
 * [[Event.record]] method is called. Timestamps don't represent any
 * absolute time, they are only meant to be compared to each other to
 * determine when an event ocurred compared to other events.
 */
class Event(implicit c: Event.Counter = Event.sharedCounter)
  extends Function0[Unit]
{
  private[this] var _marks = new ArrayBuffer[Event.Mark];

  /**
   * Requests a new time mark from [[Event.Counter]] and records it.
   */
  def record() {
    _marks += c.mark();
  }

  /**
   * An alias for [[record]].
   */
  override def apply(): Unit = record();

  /**
   * Returns an identity function whose side effect is calling [[record()]].
   */
  def id[R]: R => R =
    (x: R) => { record(); x; }

  /**
   * Calls [[record()]] and then evaluates the given computation.
   */
  def fn[R](f: => R): R =
    { record(); f; }
  /**
   * Augments a given function with calling [[record()]].
   */
  def fn0[R](f: () => R): () => R =
    () => { record(); f(); }
  /**
   * Augments a given function with calling [[record()]].
   */
  def fn1[A,R](f: (A) => R): (A) => R =
    (x: A) => { record(); f(x); }
  /**
   * Augments a given function with calling [[record()]].
   */
  def fn2[A,B,R](f: (A,B) => R): (A,B) => R =
    (x: A, y: B) => { record(); f(x, y); }

  /**
   * Returns the list of accumulated marks.
   */
  def marks: Traversable[Event.Mark] = _marks;


  /**
   * Returns the number of times this event was invoked.
   */
  def count: Int = marks.size;
  /**
   * Checks that this event was invoked just once.
   */
  def justOnce =
    count should be (1);
  /**
   * Check that this event ocurred only after all the given events.
   * Note that this condition is trivially satisfied if this event
   * has never been invoked.
   */
  def mustAfter(es: Event*) {
    for(e <- es;
        i <- e.marks;
        j <- this.marks)
      i should be < j;
  }
  /**
   * Check that this event ocurred only before all the given events.
   * Note that this condition is trivially satisfied if this event
   * has never been invoked.
   */
  def mustBefore(es: Event*) {
    for(e <- es;
        i <- e.marks;
        j <- this.marks)
      i should be > j;
  }
}

object Event {
  type Mark = Int
  class Counter {
    private[this] val lock = new Object;
    private[this] var counter: Mark = 0;

    /**
     * Create a new mark. Every time the function is called,
     * it returns a mark that his higher than the previous onme.
     */
    def mark(): Mark = lock.synchronized {
      counter += 1;
      counter;
    }
  }

  implicit val sharedCounter = new Counter;
}
