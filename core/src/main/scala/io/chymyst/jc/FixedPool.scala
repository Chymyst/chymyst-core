package io.chymyst.jc

import java.util.concurrent.atomic.AtomicInteger

import scala.language.experimental.macros

/** The fixed-thread implementation of a `Chymyst` thread pool.
  *
  * @param parallelism Total number of threads.
  */
final class FixedPool(
  name: String,
  override val parallelism: Int = cpuCores,
  priority: Int = Thread.NORM_PRIORITY,
  reporter: EventReporting = ConsoleErrorReporter
) extends Pool(name, priority, reporter) {
  private[jc] val blockingCalls = new AtomicInteger(0)

  private def deadlockCheck(): Unit =
    if (blockingCalls.get >= workerExecutor.getMaximumPoolSize)
      reporter.reportDeadlock(toString, workerExecutor.getMaximumPoolSize, blockingCalls.get, Core.getReactionInfo)

  override private[chymyst] def runReaction(name: String, closure: ⇒ Unit): Unit = {
    deadlockCheck()
    super.runReaction(name, closure)
  }

  private[jc] def startedBlockingCall(selfBlocking: Boolean) = if (selfBlocking) {
    blockingCalls.getAndIncrement()
    deadlockCheck()
  }

  private[jc] def finishedBlockingCall(selfBlocking: Boolean) = if (selfBlocking) {
    blockingCalls.getAndDecrement()
    deadlockCheck()
  }

  def withReporter(r: EventReporting): FixedPool = new FixedPool(name, parallelism, priority, r)
}

object FixedPool {
  def apply(): FixedPool = macro PoolMacros.newFixedPoolImpl0
  def apply(parallelism: Int): FixedPool = macro PoolMacros.newFixedPoolImpl1
}
