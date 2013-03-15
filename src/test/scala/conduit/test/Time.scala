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

import org.scalatest.time._

/**
 * Measures the time a computation takes in _ms_.
 */
protected object Time {
  def timeNS[R](body: => R): (R, Long) = {
    val start = System.nanoTime;
    val result: R = body;
    val time: Long = System.nanoTime - start;
    (result, time);
  }

  def timeMS[R](body: => R): (R, Long) = {
    val (result, ns) = timeNS(body);
    (result, ns / 1000000)
  }


  def timeSpan[R](body: => R): (R, Span) = {
    val (result, ns) = timeNS(body);
    (result, Span(ns, Nanoseconds));
  }

  def timeSpanU(body: => Any): Span =
    timeSpan(body)._2;
}
