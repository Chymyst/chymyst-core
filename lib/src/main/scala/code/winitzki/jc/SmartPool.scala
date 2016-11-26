package code.winitzki.jc

import java.util.concurrent._

/** This is similar to scala.concurrent.blocking and is used to annotate expressions that should lead to a possible increase of thread count.
  */
object Blocking {
  def apply[T](expr: => T): T =
    Thread.currentThread() match {
      case t: SmartThread => t.blockingCall(expr)
      case _ => expr // Blocking{...} has no effect if we are not running on a SmartThread
    }
}

class SmartThread(pool: SmartPool, r: Runnable) extends Thread(r) {
  var name: Option[String] = None
  override def toString: String = name.getOrElse(super.toString)

  def blockingCall[T](expr: => T): T ={
    pool.startedBlockingCall()
    val result = expr
    pool.finishedBlockingCall()
    result
  }
}

/** A cached pool that increases its thread count whenever a blocking molecule is injected, and decreases afterwards.
  * The {{{Blocking()}}} construct, similar to {{{scala.concurrent.blocking}}}, is used to annotate expressions that should lead to a possible increase of thread count.
  */
class SmartPool(parallellism: Int) extends Pool {

  def getPoolSize = executor.getCorePoolSize

  def startedBlockingCall() = {
    val newPoolSize = getPoolSize + 1
    executor.setMaximumPoolSize(newPoolSize)
    executor.setCorePoolSize(newPoolSize)
  }

  def finishedBlockingCall() = {
    val newPoolSize = math.max(parallellism, getPoolSize - 1)
    executor.setCorePoolSize(newPoolSize)
    executor.setMaximumPoolSize(newPoolSize)
  }

  val maxQueueCapacity = parallellism*1000 + 100

  private val queue = new LinkedBlockingQueue[Runnable](maxQueueCapacity)

  val initialThreads = parallellism
  val secondsToRecycleThread = 1
  val sleepTime = 200

  val executor = new ThreadPoolExecutor(initialThreads, parallellism, secondsToRecycleThread, TimeUnit.SECONDS,
    queue, new ThreadFactory {
      override def newThread(r: Runnable): Thread = new SmartThread(SmartPool.this, r)
    })

  override def shutdownNow(): Unit = new Thread {
    executor.shutdown()
    executor.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
    executor.shutdownNow()
    executor.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
    executor.shutdownNow()
  }

  override def runClosure(closure: => Unit, name: Option[String]): Unit =
    executor.submit(new NamedRunnable(closure, name))

  override def isInactive: Boolean = executor.isShutdown || executor.isTerminated
}
