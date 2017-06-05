package io.chymyst.jc


import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent._

import scala.concurrent.ExecutionContext

/** A pool of execution threads, or another way of running tasks (could use actors or whatever else).
  * Tasks submitted for execution can have Chymyst-specific info (useful for debugging) when scheduled using `runReaction`.
  * The pool can be shut down, in which case all further tasks will be refused.
  */
abstract class Pool(val name: String, val priority: Int) extends AutoCloseable {
  override val toString: String = s"${this.getClass.getSimpleName}:$name"

  private[jc] def startedBlockingCall(selfBlocking: Boolean): Unit

  private[jc] def finishedBlockingCall(selfBlocking: Boolean): Unit

  def parallelism: Int

  /** Run a reaction closure on the thread pool.
    * The reaction closure will be created by [[ReactionSite.reactionClosure]].
    *
    * @param closure A reaction closure to run.
    */
  private[chymyst] def runReaction(closure: => Unit): Unit

  def isInactive: Boolean = executor.isShutdown || executor.isTerminated

  override def close(): Unit = shutdownNow()

  def recycleThreadTimeMs: Long = 1000L

  def shutdownWaitTimeMs: Long = 200L

  private val threadGroupName = toString + ",thread_group"

  private val threadNameBase = toString + ",worker_thread:"

  val threadGroup: ThreadGroup = {
    val tg = new ThreadGroup(threadGroupName)
    tg.setMaxPriority(priority)
    tg
  }

  private[jc] val schedulerQueue: BlockingQueue[Runnable] = new LinkedBlockingQueue[Runnable]

  private val schedulerThreadFactory: ThreadFactory = { (r: Runnable) ⇒ new Thread(threadGroup, r, toString + ",scheduler_thread") }

  private[jc] val schedulerExecutor: ThreadPoolExecutor = {
    val executor = new ThreadPoolExecutor(1, 1, recycleThreadTimeMs, TimeUnit.MILLISECONDS, schedulerQueue, schedulerThreadFactory)
    executor.allowCoreThreadTimeOut(true)
    executor
  }

  private[jc] def runScheduler(runnable: Runnable): Unit = schedulerExecutor.execute(runnable)

  private[jc] val queue: BlockingQueue[Runnable] = new LinkedBlockingQueue[Runnable]

  private val threadFactory: ThreadFactory = { (r: Runnable) ⇒ new ChymystThread(r, Pool.this) }

  protected val executor: ThreadPoolExecutor = {
    val executor = new ThreadPoolExecutor(parallelism, parallelism, recycleThreadTimeMs, TimeUnit.MILLISECONDS, queue, threadFactory)
    executor.allowCoreThreadTimeOut(true)
    executor
  }

  val executionContext: ExecutionContext = ExecutionContext.fromExecutor(executor)

  private val currentThreadId: AtomicInteger = new AtomicInteger(0)

  private[jc] def nextThreadName: String = threadNameBase + currentThreadId.getAndIncrement().toString

  def shutdownNow(): Unit = ()

  /*def shutdownNow(): Unit = new Thread {
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
*/
}
