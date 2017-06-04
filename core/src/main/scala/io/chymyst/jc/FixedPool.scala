package io.chymyst.jc

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger

/** Basic implementation of a thread pool, typically with a fixed number of threads.
  * This class has an abstract method that produces a [[ThreadPoolExecutor]] and a [[BlockingQueue]].
  *
  * @param threads Initial number of threads.
  */
class FixedPool(threads: Int = 8) extends Pool {

  private[jc] val queue: BlockingQueue[Runnable] = new LinkedBlockingQueue[Runnable]

  protected val executor: ThreadPoolExecutor = {
    val newThreadFactory = new ThreadFactory {
      override def newThread(r: Runnable): Thread = new ChymystThread(r, FixedPool.this)
    }
    val secondsToRecycleThread = 1L
    val executor = new ThreadPoolExecutor(threads, threads, secondsToRecycleThread, TimeUnit.SECONDS, queue, newThreadFactory)
    executor.allowCoreThreadTimeOut(true)
    executor
  }

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

  override def isInactive: Boolean = executor.isShutdown || executor.isTerminated

  private[jc] override def startedBlockingCall(selfBlocking: Boolean) = if (selfBlocking) {
    blockingCalls.getAndIncrement()
    deadlockCheck()
  }

  private[jc] override def finishedBlockingCall(selfBlocking: Boolean) = if (selfBlocking) {
    blockingCalls.getAndDecrement()
    deadlockCheck()
  }

}

