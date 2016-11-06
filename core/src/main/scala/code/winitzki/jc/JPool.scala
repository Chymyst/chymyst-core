package code.winitzki.jc

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext

class JJoinPool extends JPoolExecutor(1)
class JProcessPool(threads: Int) extends JPoolExecutor(threads)

/*
class JThreadPoolExecutor(threads: Int = 1) extends JThreadPool {
  val execContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(threads))

  def runTask(task: JThreadPool => Unit) = {
    Future { task(JThreadPoolExecutor.this)}(execContext)
  }
}
*/
/* */
class JPoolExecutor(threads: Int = 8) extends JPool {
  val execContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(threads))

  def runTask(task: => Unit): Unit = execContext.execute(new Runnable {
    override def run(): Unit = task
  })
}

/* */