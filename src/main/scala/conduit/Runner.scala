package conduit

/**
 * Provides an abstraction for running a pipe and producing the final result.
 */
trait Runner {
  import Runner._

  def runPipe[R](pipe: NoInput[Unit,Nothing,R]): R =
    runPipe[Unit,Nothing,R](pipe, (), NullIO)

  def runPipe[U,O,R](pipe: NoInput[U,O,R], init: U, sender: O => Unit): R =
    runPipeO[U,Nothing,O,R](pipe, init, NullIO, sender)

  /**
   * The most general way how to run a pipe using callbacks.
   * The pipe is fed with repeated calls to `receiver`. When the receiver
   * returns `None`, the pipe is notified about end of input using `init`. The
   * output of the pipe is processed by `sender`.
   */
  def runPipeO[U,I,O,R](pipe: GenPipe[U,I,O,R], init: => U, receiver: () => Option[I], sender: O => Unit): R =
    //runPipe[U,I,O,R](pipe, () => receiver().fold[Either[U,I]]({ Left(init) })(Right _), sender);
    runPipeE[U,I,O,R](pipe, () => receiver() match {
        case Some(i) => Right(i)
        case None => Left(init)
      }, sender);

  /**
   * The most general way how to run a pipe using callbacks.
   * The pipe is fed with repeated calls
   * to `receiver`. When `receiver` returns `Left(u)` the first time, the pipe
   * receives no more input and is notified about it using the value of `u`.
   * The output of the pipe is processed by `sender`.
   */
  def runPipeE[U,I,O,R](pipe: GenPipe[U,I,O,R], receiver: () => Either[U,I], sender: O => Unit): R;
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
