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

import collection.mutable.{ArrayBuffer, Buffer}

import org.scalatest._
import org.scalatest.PropSpec
import org.scalatest.prop.PropertyChecks
import org.scalatest.matchers.ShouldMatchers

import conduit._
import conduit.Pipe._

class TestPipe
  extends PropSpec with PropertyChecks with ShouldMatchers
{
  property("runPipe on done returns the original value") {
    forAll { (x: Int) =>
      runPipe(done(x)) should be === x
    }
    forAll { (x: String) =>
      runPipe(done(x)) should be === x
    }
  }

  property("doneF should run its finalizer") {
    val buf = new ArrayBuffer[Option[Throwable]];
    runPipe(doneF((), Finalizer(buf += _))) should be (());
    buf should be (ArrayBuffer(None));
  }


  property("delay should run body only when invoked") {
    val buf = new ArrayBuffer[Int];
    val p = delay({ buf += 0; done })(Finalizer({ buf += 2; () }))
    buf += 1;
    runPipe(p) should be (());
    buf should be (ArrayBuffer(1, 0));
  }
  property("delay should run finalizer when exception occurs") {
    val buf = new ArrayBuffer[Int];
    buf += 1;
    evaluating {
      runPipe(delay(throw new RuntimeException)(Finalizer({ buf += 2; () })));
    } should produce[RuntimeException];
    buf should be (ArrayBuffer(1, 2));
  }

  // TODO
}
