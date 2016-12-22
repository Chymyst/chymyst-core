package code.winitzki.jc

import java.util.concurrent.ThreadFactory

class SmartThread(runnable: Runnable, pool: SmartPool) extends ThreadWithInfo(runnable) {
  private var inBlockingCall: Boolean = false

  /** Given that the expression {{{expr}}} is "idle blocking", the thread pool will increase the parallelism.
    * This method always runs on {{{this}}} thread, so no need to synchronize the mutation of {{{var inBlockingCall}}}.
    *
    * @param expr Expression that will be idle blocking.
    * @tparam T Type of value of this expression.
    * @return The same result as the expression would return.
    */
  private[jc] def blockingCall[T](expr: => T): T = if (inBlockingCall) expr else {
    inBlockingCall = true
    pool.startedBlockingCall()
    val result = expr
    pool.finishedBlockingCall()
    this.synchronized( inBlockingCall = false )
    result
  }

}

/** Thread that knows how JoinRun uses it at any time.
  * The {{{reactionInfo}}} variable is initially set to None, and will be set to Some(...) whenever JoinRun task runs on this thread.
  *
  * @param runnable The initial task given to the thread. (Required by the Thread interface.)
  */
class ThreadWithInfo(runnable: Runnable) extends Thread(runnable) {
  @volatile var reactionInfo: Option[ReactionInfo] = None
}

class RunnableWithInfo(closure: => Unit, val info: ReactionInfo) extends Runnable {
  override def toString: String = info.toString
  override def run(): Unit = {
    Thread.currentThread match {
      case t: ThreadWithInfo =>
        t.reactionInfo = Some(info)
      case _ =>
    }
    closure
  }
}

class ThreadFactoryWithInfo extends ThreadFactory {
  override def newThread(r: Runnable): Thread = {
    new ThreadWithInfo(r)
  }
}
