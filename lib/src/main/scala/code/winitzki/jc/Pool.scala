package code.winitzki.jc


import java.util.concurrent._

import code.winitzki.jc.JoinRun.ReactionInfo

import scala.concurrent.{ExecutionContext, Future}

class CachedPool(threads: Int) extends PoolExecutor(threads,
  t => new ThreadPoolExecutor(1, t, 1L, TimeUnit.SECONDS, new SynchronousQueue[Runnable], new ThreadFactoryWithInfo)
)

class FixedPool(threads: Int) extends PoolExecutor(threads,
  t => new ThreadPoolExecutor(t, t, 0L, TimeUnit.SECONDS, new LinkedBlockingQueue[Runnable], new ThreadFactoryWithInfo)
)

/** A pool of execution threads, or another way of running tasks (could use actors or whatever else).
  *  Tasks submitted for execution can have an optional name (useful for debugging).
  *  The pool can be shut down, in which case all further tasks will be refused.
  */
trait Pool {
  def shutdownNow(): Unit

  def runClosure(closure: => Unit, info: ReactionInfo): Unit

  def isActive: Boolean = !isInactive
  def isInactive: Boolean

  def canMakeThreads: Boolean = true
}

private[jc] class PoolExecutor(threads: Int = 8, execFactory: Int => ExecutorService) extends Pool {
  protected val execService = execFactory(threads)

  val sleepTime = 200

  def shutdownNow() = new Thread {
    try{
      execService.shutdown()
      execService.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
    } finally  {
      execService.shutdownNow()
      execService.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
      execService.shutdownNow()
    }
  }

  def runClosure(closure: => Unit, info: ReactionInfo): Unit =
    execService.execute(new RunnableWithInfo(closure, info))

  override def isInactive: Boolean = execService.isShutdown || execService.isTerminated
}

// Not used now.
private[jc] class PoolFutureExecutor(threads: Int = 8, execFactory: Int => ExecutorService) extends PoolExecutor(threads, execFactory) {
  private val execContext = ExecutionContext.fromExecutor(execService)

  override def runClosure(closure: => Unit, info: ReactionInfo): Unit =
    Future { closure }(execContext)
}

/** Create a pool from a Handler interface. The pool will submit tasks using a Handler.post() method.
  *
  * This is useful for Android and JavaFX environments. Not yet tested. Behavior with singletons will be probably wrong.
  */
class HandlerPool(handler: { def post(r: Runnable): Unit }) extends Pool {
  override def shutdownNow(): Unit = ()

  override def runClosure(closure: => Unit, info: ReactionInfo): Unit =
    handler.post(new RunnableWithInfo(closure, info))

  override def isInactive: Boolean = false
}