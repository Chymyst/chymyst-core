package io.chymyst.jc

import java.util.concurrent.ThreadFactory

private[jc] final class SmartThread(runnable: Runnable, pool: SmartPool) extends ThreadWithInfo(runnable) {
  private var inBlockingCall: Boolean = false

  /** Given that the expression `expr` is "idle blocking", the thread pool will increase the parallelism.
    * This method always runs on `this` thread, so no need to synchronize the mutation of `var inBlockingCall`.
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
    inBlockingCall = false
    result
  }
}

/** Thread that knows how Chymyst uses it at any time.
  * The `chymystInfo` variable is initially set to `None`, and will be set to `Some(...)` whenever a Chymyst reaction runs on this thread.
  *
  * @param runnable The initial task given to the thread. (Required by the [[Thread]] interface.)
  */
private[jc] sealed class ThreadWithInfo(runnable: Runnable) extends Thread(runnable) {
  @volatile var chymystInfo: Option[ChymystThreadInfo] = None
}

private[jc] final class RunnableWithInfo(closure: => Unit, info: ChymystThreadInfo) extends Runnable {
  override def toString: String = info.toString

  override def run(): Unit = {
    Thread.currentThread match {
      case t: ThreadWithInfo =>
        t.chymystInfo = Some(info)
      case _ =>
    }
    closure
  }
}

private[jc] final class ThreadFactoryWithInfo extends ThreadFactory {
  override def newThread(r: Runnable): Thread = {
    new ThreadWithInfo(r)
  }
}
