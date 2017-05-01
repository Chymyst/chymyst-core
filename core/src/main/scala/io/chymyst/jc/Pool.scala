package io.chymyst.jc


import java.util.concurrent._

class FixedPool(threads: Int) extends PoolExecutor(threads, { t =>
  val queue = new LinkedBlockingQueue[Runnable]
  val secondsToRecycleThread = 1L
  val executor = new ThreadPoolExecutor(t, t, secondsToRecycleThread, TimeUnit.SECONDS, queue, new ThreadFactoryWithInfo)
  executor.allowCoreThreadTimeOut(true)
  (executor, queue)
})

/** A pool of execution threads, or another way of running tasks (could use actors or whatever else).
  * Tasks submitted for execution can have Chymyst-specific info (useful for debugging) when scheduled using `runReaction`.
  * The pool can be shut down, in which case all further tasks will be refused.
  */
trait Pool extends AutoCloseable {
  def shutdownNow(): Unit

  /** Run a reaction closure on the thread pool.
    * The reaction closure will be created by [[ReactionSite.buildReactionClosure]].
    *
    * @param closure A reaction closure to run.
    * @param info    The reaction info for debugging and run-time sanity checking purposes.
    */
  def runReaction(closure: => Unit, info: ChymystThreadInfo): Unit

  /** Run a plain Java [[Runnable]] on the thread pool.
    *
    * @param runnable An instance of [[Runnable]].
    */
  def runRunnable(runnable: Runnable): Unit

  def isInactive: Boolean

  override def close(): Unit = shutdownNow()
}

/** Basic implementation of a thread pool.
  *
  * @param threads     Initial number of threads.
  * @param execFactory Dependency injection closure.
  */
private[jc] class PoolExecutor(threads: Int = 8, execFactory: Int => (ExecutorService, BlockingQueue[Runnable])) extends Pool {
  protected val (execService: ExecutorService, queue: BlockingQueue[Runnable]) = execFactory(threads)

  val sleepTime = 200L

  def shutdownNow(): Unit = new Thread {
    try {
      queue.clear()
      execService.shutdown()
      execService.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
    } finally {
      execService.shutdownNow()
      execService.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
      execService.shutdownNow()
      ()
    }
  }.start()

  def runReaction(closure: => Unit, info: ChymystThreadInfo): Unit =
    execService.execute(new RunnableWithInfo(closure, info))

  override def isInactive: Boolean = execService.isShutdown || execService.isTerminated

  override def runRunnable(runnable: Runnable): Unit = execService.execute(runnable)
}
