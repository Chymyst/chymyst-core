package code.winitzki.jc


import java.util.concurrent.{ExecutorService, Executors, SynchronousQueue, ThreadPoolExecutor, TimeUnit}

import code.winitzki.jc.JoinRun.{Reaction, ReactionBody}

import scala.concurrent.{ExecutionContext, Future}

class CachedPool(threads: Int) extends PoolExecutor(threads,
  t => new ThreadPoolExecutor(1, t, 1L, TimeUnit.SECONDS, new SynchronousQueue[Runnable])
)

class FixedPool(threads: Int) extends PoolExecutor(threads, Executors.newFixedThreadPool)

/** A pool of execution threads, or another way of running tasks (could use actors or whatever else).
  *  Tasks submitted for execution can have an optional name (useful for debugging).
  */
trait Pool {
  def shutdownNow(): Unit

  def runClosure(closure: => Unit, name: Option[String] = None): Unit

  /** Convenience syntax for assigning reactions to thread pools.
    * Example: {{{ threadPool123 { case a(x) + b(y) => ...} }}
    *
    * @param r Reaction body
    * @return Reaction value with default parameters and thread pool set to {{{this}}}.
    */
  def apply(r: ReactionBody): Reaction = Reaction(r, Some(this))

  def isActive: Boolean = !isInactive
  def isInactive: Boolean

}

abstract class NamedPool(val name: String) extends Pool {
  override def toString: String = s"Pool[$name]"
}

private[jc] class PoolExecutor(threads: Int = 8, execFactory: Int => ExecutorService) extends Pool {
  protected val execService = execFactory(threads)

  val sleepTime = 200

  def shutdownNow() = new Thread {
    execService.shutdown()
    execService.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
    execService.shutdownNow()
    execService.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
    execService.shutdownNow()
  }

  def runClosure(closure: => Unit, name: Option[String] = None): Unit =
    execService.execute(new NamedRunnable(closure, name))

  override def isInactive: Boolean = execService.isShutdown || execService.isTerminated
}

private[jc] class PoolFutureExecutor(threads: Int = 8, execFactory: Int => ExecutorService) extends PoolExecutor(threads, execFactory) {
  private val execContext = ExecutionContext.fromExecutor(execService)

  override def runClosure(closure: => Unit, name: Option[String] = None): Unit =
    Future { closure }(execContext)
}

class NamedRunnable(closure: => Unit, name: Option[String] = None) extends Runnable {
  override def toString: String = name.getOrElse(super.toString)
  override def run(): Unit = closure
}