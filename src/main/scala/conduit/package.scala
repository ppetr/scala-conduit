/**
 * The core trait of this library is [[conduit.GenPipe]] which represents a
 * single piece of computation. It's implementation is hidden, all operations
 * on its instances are performed using methods in [[conduit.Pipe$ conduit.Pipe]] object.
 */
package object conduit {
  /**
   * A simplified pipe that doesn't care about the result of its upstream.
   *
   * It is usually suffucient for most purposes.
   */
  type Pipe[-I,+O,+R]     = GenPipe[Any,I,O,R]
  /**
   * A pipe that cannot produce any output, only a final result.
   */
  type Sink[-I,+R]        = Pipe[I,Nothing,R]
  /**
   * A pipe that cares neither about what input it receives nor the result of
   * its upstream.
   */
  type Source[+O,+R]      = Pipe[Any,O,R]
  /**
   * A pipe that ''cannot'' receive any input. The difference over
   * [[conduit.Source]] is that `Source` can serve as downstream for anything,
   * while `NoInput` can serve as downstream only for a pipe with no output
   * ([[conduit.Sink]]). `NoInput` cares about the final result of its
   * upstream.
   */
  type NoInput[-U,+O,+R]  = GenPipe[U,Nothing,O,R]

  /**
   * Represents a pipe that feeds a part of its output back as its own input.
   * This is primarily useful for implementing leftovers, when a component
   * needs to return a part of its input back.
   */
  type Feedback[-U,I,+O,+R] = GenPipe[U,I,Either[I,O],R]
}
