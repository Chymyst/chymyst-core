package io.chymyst.jc


import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger

class FixedPool(threads: Int) extends PoolExecutor(threads) {
  protected override def execFactory(threads: Int): (ExecutorService, BlockingQueue[Runnable]) = {
    val queue = new LinkedBlockingQueue[Runnable]
    val newThreadFactory = new ThreadFactory {
      override def newThread(r: Runnable): Thread = new SmartThread(r, FixedPool.this)
    }
    val secondsToRecycleThread = 1L
    val executor = new ThreadPoolExecutor(threads, threads, secondsToRecycleThread, TimeUnit.SECONDS, queue, newThreadFactory)
    executor.allowCoreThreadTimeOut(true)
    (executor, queue)
  }
}

/** A pool of execution threads, or another way of running tasks (could use actors or whatever else).
  * Tasks submitted for execution can have Chymyst-specific info (useful for debugging) when scheduled using `runReaction`.
  * The pool can be shut down, in which case all further tasks will be refused.
  */
trait Pool extends AutoCloseable {
  def shutdownNow(): Unit

  private[jc] def startedBlockingCall(infoOpt: Option[ChymystThreadInfo]): Unit

  private[jc] def finishedBlockingCall(infoOpt: Option[ChymystThreadInfo]): Unit

  /** Run a reaction closure on the thread pool.
    * The reaction closure will be created by [[ReactionSite.buildReactionClosure]].
    *
    * @param closure A reaction closure to run.
    * @param info    The reaction info for debugging and run-time sanity checking purposes.
    */
  private[jc] def runReaction(closure: => Unit, info: ChymystThreadInfo): Unit

  def isInactive: Boolean

  override def close(): Unit = shutdownNow()

  private val schedulerExecutor: ThreadPoolExecutor = Core.newSingleThreadedExecutor

  def runScheduler(runnable: Runnable): Unit = schedulerExecutor.execute(runnable)
}

/** Basic implementation of a thread pool, typically with a fixed number of threads.
  * This class has an abstract method that produces a [[ThreadPoolExecutor]] and a [[BlockingQueue]].
  *
  * @param threads Initial number of threads.
  */
private[jc] abstract class PoolExecutor(threads: Int = 8) extends Pool {

  protected def execFactory(threads: Int): (ExecutorService, BlockingQueue[Runnable])

  protected val (executor: ThreadPoolExecutor, queue: BlockingQueue[Runnable]) = execFactory(threads)

  val blockingCalls = new AtomicInteger(0)

  val sleepTime = 200L

  def shutdownNow(): Unit = new Thread {
    try {
      queue.clear()
      executor.shutdown()
      executor.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
      executor.shutdownNow()
      ()
    }
  }.start()

  private[jc] def deadlockCheck(infoOpt: Option[ChymystThreadInfo]): Unit = {
    val deadlock = blockingCalls.get >= executor.getMaximumPoolSize
    if (deadlock) {
      val message = s"Error: deadlock occurred in fixed pool (${executor.getMaximumPoolSize} threads) due to ${blockingCalls.get} concurrent blocking calls, reaction: ${infoOpt.getOrElse("<none>").toString}"
      Core.logError(message, print = true)
    }
  }

  private[jc] def runReaction(closure: => Unit, info: ChymystThreadInfo): Unit = {
    deadlockCheck(Some(info))
    executor.execute(new RunnableWithInfo(closure, info))
  }

  override def isInactive: Boolean = executor.isShutdown || executor.isTerminated

  private[jc] override def startedBlockingCall(infoOpt: Option[ChymystThreadInfo]) = {
    blockingCalls.getAndIncrement()
    deadlockCheck(infoOpt)
  }

  private[jc] override def finishedBlockingCall(infoOpt: Option[ChymystThreadInfo]) = {
    blockingCalls.getAndDecrement()
    deadlockCheck(infoOpt)
  }
}
