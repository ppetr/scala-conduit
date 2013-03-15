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
import org.scalatest.time._
import org.scalatest.concurrent._

import conduit._
import conduit.Pipe._
import conduit.Util._

class TestUtil extends FunSuite with Timeouts {

  test("simple feedback test by processing strings to chars") {

    val feedbackTest = {
      implicit val fin = Finalizer.empty;
      def loop: Pipe[String,Either[String,Char],Unit] =
        requestI(x => if (x.length >= 1)
          respond(Right(x.charAt(0)), respond(Left(x.substring(1)), loop))
        else
          loop
        )
      loop
    }

    val col = runPipe(
      Seq("Abcd, ", "kocka prede. ", "Kocour mota, ", "pes pocita.").toSource >->
      feedback(feedbackTest) >->
      toCol(new collection.mutable.ArrayBuffer[Char])
    );

    assert(col.mkString === "Abcd, kocka prede. Kocour mota, pes pocita.")
  }

  test("a long running pipe with 1000000 inputs") {
    import Span._
    import Time._
    failAfter(Span(60, Seconds)) {
      val (_, time) = timeMS {
        val sum = runPipe(
          (1L to 1000000L).toSource >->
          foldF[Long,Long](_ + _, 0L) >->
          chkInterrupted
        );
        assert(sum === 500000500000L);
      }
      info("Took %dms".format(time));
    }
  }
}
