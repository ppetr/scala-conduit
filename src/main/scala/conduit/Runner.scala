package conduit

/**
 * Provides an abstraction for running a pipe and producing the final result.
 */
trait Runner {
  import Runner._

  def runPipe[R](pipe: NoInput[Unit,Nothing,R]): R =
    runPipe[Unit,Nothing,R](pipe, (), NullIO)

  def runPipe[U,O,R](pipe: NoInput[U,O,R], init: U, sender: O => Unit): R =
    runPipe[U,Nothing,O,R](pipe, init, NullIO, sender)
  def runPipe[U,I,O,R](pipe: GenPipe[U,I,O,R], init: U, receiver: () => Option[I], sender: O => Unit): R;
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
