package io.chymyst.jc

import scala.language.experimental.macros

/** This is similar to scala.concurrent.blocking and is used to annotate expressions that should lead to a possible increase of thread count.
  * Multiple nested calls to `BlockingIdle` are equivalent to one call.
  */
object BlockingIdle {
  def apply[T](expr: => T): T = apply(selfBlocking = false)(expr)

  private[jc] def apply[T](selfBlocking: Boolean)(expr: => T): T =
    Thread.currentThread() match {
      case t: ChymystThread => t.blockingCall(expr, selfBlocking)
      case _ => expr // BlockingIdle{...} has no effect if we are not running on a `ChymystThread`.
    }
}

/** A cached pool that increases its thread count whenever a blocking molecule is emitted, and decreases afterwards.
  * The `BlockingIdle` function, similar to `scala.concurrent.blocking`, is used to annotate expressions that should lead to an increase of thread count, and to a decrease of thread count once the idle blocking call returns.
  * @param parallelism Initial number of threads.
  */
final class BlockingPool(
  name: String,
  override val parallelism: Int = cpuCores,
  priority: Int = Thread.NORM_PRIORITY,
  reporter: EventReporting = ConsoleErrorReporter
) extends Pool(name, priority, reporter) {

  // Looks like we will die hard at about 2021 threads...
  val poolSizeLimit: Int = math.min(2000, 1000 + 2 * parallelism)

  def currentPoolSize: Int = workerExecutor.getCorePoolSize

  private[jc] def startedBlockingCall(selfBlocking: Boolean) = synchronized { // Need a lock to modify the pool sizes.
    val newPoolSize = math.min(currentPoolSize + 1, poolSizeLimit)
    if (newPoolSize > currentPoolSize) {
      workerExecutor.setMaximumPoolSize(newPoolSize)
      workerExecutor.setCorePoolSize(newPoolSize)
    } else {
      reporter.warnTooManyThreads(toString, currentPoolSize)
    }
  }

  private[jc] def finishedBlockingCall(selfBlocking: Boolean) = synchronized { // Need a lock to modify the pool sizes.
    val newPoolSize = math.max(parallelism, currentPoolSize - 1)
    workerExecutor.setCorePoolSize(newPoolSize) // Must set them in this order, so that the core pool size is never larger than the maximum pool size.
    workerExecutor.setMaximumPoolSize(newPoolSize)
  }

  def withReporter(r: EventReporting): BlockingPool = new BlockingPool(name, parallelism, priority, r)
}

object BlockingPool {
  def apply(): BlockingPool = macro PoolMacros.newBlockingPoolImpl0
  def apply(parallelism: Int): BlockingPool = macro PoolMacros.newBlockingPoolImpl1
}
