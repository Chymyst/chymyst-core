package code.winitzki.jc

import java.util.concurrent._

/** This is similar to scala.concurrent.blocking and is used to annotate expressions that should lead to a possible increase of thread count.
  * Multiple nested calls to {{{BlockingIdle}}} are equivalent to one call.
  */
object BlockingIdle {
  def apply[T](expr: => T): T =
    Thread.currentThread() match {
      case t: SmartThread => t.blockingCall(expr)
      case _ => expr // BlockingIdle{...} has no effect if we are not running on a SmartThread
    }
}

class SmartThread(r: Runnable, pool: SmartPool) extends Thread(r) {
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

/** A cached pool that increases its thread count whenever a blocking molecule is injected, and decreases afterwards.
  * The {{{BlockingIdle}}} function, similar to {{{scala.concurrent.blocking}}}, is used to annotate expressions that should lead to an increase of thread count, and to a decrease of thread count once the idle blocking call returns.
  */
class SmartPool(parallellism: Int) extends Pool {

  def currentPoolSize = executor.getCorePoolSize

  private[jc] def startedBlockingCall() = synchronized {
    val newPoolSize = currentPoolSize + 1
    executor.setMaximumPoolSize(newPoolSize)
    executor.setCorePoolSize(newPoolSize)
  }

  private[jc] def finishedBlockingCall() = synchronized {
    val newPoolSize = math.max(parallellism, currentPoolSize - 1)
    executor.setCorePoolSize(newPoolSize) // Must set them in this order, so that the core pool size is never larger than the maximum pool size.
    executor.setMaximumPoolSize(newPoolSize)
  }

  val maxQueueCapacity = parallellism*1000 + 100

  private val queue = new LinkedBlockingQueue[Runnable](maxQueueCapacity)

  val initialThreads = parallellism
  val secondsToRecycleThread = 1
  val shutdownWaitTimeMs = 200

  private val executor = new ThreadPoolExecutor(initialThreads, parallellism, secondsToRecycleThread, TimeUnit.SECONDS,
    queue, new ThreadFactory {
      override def newThread(r: Runnable): Thread = new SmartThread(r, SmartPool.this)
    })

  override def shutdownNow(): Unit = new Thread {
    executor.shutdown()
    executor.awaitTermination(shutdownWaitTimeMs, TimeUnit.MILLISECONDS)
    executor.shutdownNow()
    executor.awaitTermination(shutdownWaitTimeMs, TimeUnit.MILLISECONDS)
    executor.shutdownNow()
  }

  override def runClosure(closure: => Unit, name: Option[String]): Unit =
    executor.submit(new NamedRunnable(closure, name))

  override def isInactive: Boolean = executor.isShutdown || executor.isTerminated
}
