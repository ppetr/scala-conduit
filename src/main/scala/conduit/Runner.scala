package conduit

/**
 * Provides an abstraction for running a pipe and producing the final result.
 */
trait Runner {
  import Runner._

  def runPipe[R](pipe: Pipe.NoInput[Unit,Nothing,R]): R =
    runPipe[Nothing,R](pipe, NullIO)

  def runPipe[O,R](pipe: Pipe.NoInput[Unit,O,R], sender: O => Unit): R =
    runPipe[Nothing,O,R](pipe, NullIO, sender)
  def runPipe[I,O,R](pipe: GenPipe[Unit,I,O,R], receiver: () => Option[I], sender: O => Unit): R;
}

object Runner {
  object NullIO
    extends Function1[Nothing,Unit]
    with Function0[Option[Nothing]]
  {
    override final def apply(x: Nothing) = {} // never occurs
    override final def apply(): Option[Nothing] = None
  }
}
