package code.winitzki.jc

import java.util.concurrent.ThreadFactory

import code.winitzki.jc.JoinRun.ReactionOrInjectionInfo


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

class ThreadWithInfo(runnable: Runnable) extends Thread(runnable) {
  val runnableInfo: Option[ReactionOrInjectionInfo] = runnable match {
    case r: RunnableWithInfo => Some(r.info)
    case _ => None
  }
}

class RunnableWithInfo(closure: => Unit, val info: ReactionOrInjectionInfo) extends Runnable {
  override def toString: String = info.toString
  override def run(): Unit = closure
}

class ThreadFactoryWithInfo extends ThreadFactory {
  override def newThread(r: Runnable): Thread = new ThreadWithInfo(r)
}
