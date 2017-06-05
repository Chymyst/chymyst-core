package io.chymyst.jc

import java.util.concurrent.atomic.AtomicInteger

import scala.language.experimental.macros

/** The fixed-thread implementation of a `Chymyst` thread pool.
  *
  * @param parallelism Total number of threads.
  */
final class FixedPool(name: String, override val parallelism: Int = cpuCores, priority: Int = Thread.NORM_PRIORITY) extends Pool(name, priority) {
  private[jc] val blockingCalls = new AtomicInteger(0)

  private[jc] def deadlockCheck(): Unit = {
    val deadlock = blockingCalls.get >= executor.getMaximumPoolSize
    if (deadlock) {
      val message = s"Error: deadlock occurred in fixed pool (${executor.getMaximumPoolSize} threads) due to ${blockingCalls.get} concurrent blocking calls, reaction: ${Core.getReactionInfo}"
      Core.logError(message, print = true)
    }
  }

  private[chymyst] def runReaction(closure: => Unit): Unit = {
    deadlockCheck()
    executor.execute { () ⇒ closure }
  }

  private[jc] override def startedBlockingCall(selfBlocking: Boolean) = if (selfBlocking) {
    blockingCalls.getAndIncrement()
    deadlockCheck()
  }

  private[jc] override def finishedBlockingCall(selfBlocking: Boolean) = if (selfBlocking) {
    blockingCalls.getAndDecrement()
    deadlockCheck()
  }

}

object FixedPool {
  def apply(): FixedPool = macro PoolMacros.newFixedPoolImpl0 // IntelliJ cannot resolve the symbol PoolMacros, but compilation works.
  def apply(parallelism: Int): FixedPool = macro PoolMacros.newFixedPoolImpl1 // IntelliJ cannot resolve the symbol PoolMacros, but compilation works.
}