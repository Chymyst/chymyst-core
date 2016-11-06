package sample

import java.util.concurrent.Executors

import sample.JoinDef.{JReaction, JUnapplyArg}

import scala.concurrent.{ExecutionContext, Future}

// A pool of execution threads, or another way of running tasks (could use actors or whatever else).

trait JThreadPool {
  def runTask(task: JThreadPool => Unit): Unit
  def apply(body: PartialFunction[JUnapplyArg, Any]): JReaction = JReaction(body, this)
}

class JThreadPoolForJoins extends JThreadPoolExecutor(1)
class JThreadPoolForReactions(threads: Int) extends JThreadPoolExecutor(threads)

/*
class JThreadPoolExecutor(threads: Int = 1) extends JThreadPool {
  val execContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(threads))

  def runTask(task: JThreadPool => Unit) = {
    Future { task(JThreadPoolExecutor.this)}(execContext)
  }
}
*/
/* */
class JThreadPoolExecutor(threads: Int = 8) extends JThreadPool {
  val execContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(threads))

  def runTask(task: JThreadPool => Unit): Unit = execContext.execute(new Runnable {
    override def run(): Unit = task(JThreadPoolExecutor.this)
  })
}

/* */