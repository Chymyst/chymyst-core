package io.chymyst.jc

import java.util.concurrent._

import io.chymyst.jc.Core.logMessage

/** This is similar to scala.concurrent.blocking and is used to annotate expressions that should lead to a possible increase of thread count.
  * Multiple nested calls to `BlockingIdle` are equivalent to one call.
  */
object BlockingIdle {
  def apply[T](expr: => T): T = apply(selfBlocking = false)(expr)

  private[jc] def apply[T](selfBlocking: Boolean)(expr: => T): T =
    Thread.currentThread() match {
      case t: SmartThread => t.blockingCall(expr, selfBlocking)
      case _ => expr // BlockingIdle{...} has no effect if we are not running on a SmartThread
    }
}

/** A cached pool that increases its thread count whenever a blocking molecule is emitted, and decreases afterwards.
  * The `BlockingIdle` function, similar to `scala.concurrent.blocking`, is used to annotate expressions that should lead to an increase of thread count, and to a decrease of thread count once the idle blocking call returns.
  */
class SmartPool(parallelism: Int = cpuCores) extends Pool {

  // Looks like we will die hard at about 2021 threads...
  val maxPoolSize: Int = 1000 + 2 * parallelism

  def currentPoolSize: Int = executor.getCorePoolSize

  private[jc] override def startedBlockingCall(infoOpt: Option[ChymystThreadInfo], selfBlocking: Boolean) = synchronized {
    val newPoolSize = math.min(currentPoolSize + 1, maxPoolSize)
    if (newPoolSize > currentPoolSize) {
      executor.setMaximumPoolSize(newPoolSize)
      executor.setCorePoolSize(newPoolSize)
    } else {
      logMessage(s"Chymyst Core warning: In $this: It is dangerous to increase the pool size, which is now $currentPoolSize. Memory is ${Runtime.getRuntime.maxMemory}")
    }
  }

  private[jc] override def finishedBlockingCall(infoOpt: Option[ChymystThreadInfo], selfBlocking: Boolean) = synchronized {
    val newPoolSize = math.max(parallelism, currentPoolSize - 1)
    executor.setCorePoolSize(newPoolSize) // Must set them in this order, so that the core pool size is never larger than the maximum pool size.
    executor.setMaximumPoolSize(newPoolSize)
  }

  val initialThreads: Int = parallelism
  val secondsToRecycleThread = 1L
  val shutdownWaitTimeMs = 200L

  protected val executor = {
    val newThreadFactory = new ThreadFactory {
      override def newThread(r: Runnable): Thread = new SmartThread(r, SmartPool.this)
    }
    val queue = new LinkedBlockingQueue[Runnable]
    val executor = new ThreadPoolExecutor(initialThreads, parallelism, secondsToRecycleThread, TimeUnit.SECONDS, queue, newThreadFactory)
    executor.allowCoreThreadTimeOut(true)
    executor
  }

  override def shutdownNow(): Unit = new Thread {
    try {
      executor.getQueue.clear()
      executor.shutdown()
      executor.awaitTermination(shutdownWaitTimeMs, TimeUnit.MILLISECONDS)
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(shutdownWaitTimeMs, TimeUnit.MILLISECONDS)
      executor.shutdownNow()
      ()
    }
  }.start()

  private[chymyst] override def runReaction(closure: => Unit, info: ChymystThreadInfo): Unit =
    executor.execute(new RunnableWithInfo(closure, info))

  override def isInactive: Boolean = executor.isShutdown || executor.isTerminated
}
