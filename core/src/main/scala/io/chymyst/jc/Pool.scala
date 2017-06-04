package io.chymyst.jc


import java.util.concurrent.ThreadPoolExecutor

import scala.concurrent.ExecutionContext

/** A pool of execution threads, or another way of running tasks (could use actors or whatever else).
  * Tasks submitted for execution can have Chymyst-specific info (useful for debugging) when scheduled using `runReaction`.
  * The pool can be shut down, in which case all further tasks will be refused.
  */
trait Pool extends AutoCloseable {
  def shutdownNow(): Unit

  private[jc] def startedBlockingCall(selfBlocking: Boolean): Unit

  private[jc] def finishedBlockingCall(selfBlocking: Boolean): Unit

  /** Run a reaction closure on the thread pool.
    * The reaction closure will be created by [[ReactionSite.reactionClosure]].
    *
    * @param closure A reaction closure to run.
    */
  private[chymyst] def runReaction(closure: => Unit): Unit

  def isInactive: Boolean

  override def close(): Unit = shutdownNow()

  private val schedulerExecutor: ThreadPoolExecutor = Core.newSingleThreadedExecutor

  protected def executor: ThreadPoolExecutor

  val executionContext: ExecutionContext = ExecutionContext.fromExecutor(executor)

  def runScheduler(runnable: Runnable): Unit = schedulerExecutor.execute(runnable)
}
