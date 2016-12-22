package code.chymyst.jc

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

/** A cached pool that increases its thread count whenever a blocking molecule is emitted, and decreases afterwards.
  * The {{{BlockingIdle}}} function, similar to {{{scala.concurrent.blocking}}}, is used to annotate expressions that should lead to an increase of thread count, and to a decrease of thread count once the idle blocking call returns.
  */
class SmartPool(parallelism: Int) extends Pool {

  private def newThreadFactory: ThreadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = new SmartThread(r, SmartPool.this)
  }

  def currentPoolSize: Int = executor.getCorePoolSize

  private[jc] def startedBlockingCall() = synchronized {
    val maxThreads = 1000 + 2*parallelism // Looks like we will die hard at about 2021 threads...
    val newPoolSize = math.min(currentPoolSize + 1, maxThreads)
    if (newPoolSize > currentPoolSize) {
      executor.setMaximumPoolSize(newPoolSize)
      executor.setCorePoolSize(newPoolSize)
    } else {
      println(s"JoinRun warning:In $this: It is dangerous to increase the pool size, which is now $currentPoolSize. Memory is ${Runtime.getRuntime.maxMemory}")
    }
  }

  private[jc] def finishedBlockingCall() = synchronized {
    val newPoolSize = math.max(parallelism, currentPoolSize - 1)
    executor.setCorePoolSize(newPoolSize) // Must set them in this order, so that the core pool size is never larger than the maximum pool size.
    executor.setMaximumPoolSize(newPoolSize)
  }

  val maxQueueCapacity: Int = parallelism*1000 + 100

  private val queue = new ArrayBlockingQueue[Runnable](maxQueueCapacity)

  val initialThreads: Int = parallelism
  val secondsToRecycleThread = 1L
  val shutdownWaitTimeMs = 200L

  private val executor = new ThreadPoolExecutor(initialThreads, parallelism, secondsToRecycleThread, TimeUnit.SECONDS, queue, newThreadFactory)

  override def shutdownNow(): Unit = new Thread {
    try {
      executor.shutdown()
      executor.awaitTermination(shutdownWaitTimeMs, TimeUnit.MILLISECONDS)
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(shutdownWaitTimeMs, TimeUnit.MILLISECONDS)
      executor.shutdownNow()
      ()
    }
  }.start()

  override def runClosure(closure: => Unit, info: ReactionInfo): Unit = {
    executor.submit(new RunnableWithInfo(closure, info))
    ()
  }

  override def isInactive: Boolean = executor.isShutdown || executor.isTerminated
}
