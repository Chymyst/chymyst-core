package io.chymyst.jc

/** Thread that knows which Chymyst reaction is running on it, and which pool it belongs to.
  * This is used for debugging and for implementing [[BlockingIdle]] functionality.
  *
  * @param runnable The initial task given to the thread. (Required by the [[Thread]] interface.)
  */
private[jc] final class ChymystThread(runnable: Runnable, val pool: Pool) extends Thread(pool.threadGroup, runnable, pool.nextThreadName) {
  private var inBlockingCall: Boolean = false

  private[jc] var reactionInfoString: String = Core.NO_REACTION_INFO_STRING

  def reactionInfo: String = reactionInfoString

  /** Given that the expression `expr` is "idle blocking", the thread pool will increase the parallelism.
    * This method always runs on `this` thread, so no need to synchronize the mutation of `var inBlockingCall`.
    *
    * @param expr Expression that will be idle blocking.
    * @tparam T Type of value of this expression.
    * @return The same result as the expression would return.
    */
  private[jc] def blockingCall[T](expr: => T, selfBlocking: Boolean = false): T = if (inBlockingCall) expr else {
    inBlockingCall = true
    pool.startedBlockingCall(selfBlocking)
    val result = expr
    pool.finishedBlockingCall(selfBlocking)
    inBlockingCall = false
    result
  }
}
