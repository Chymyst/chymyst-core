package code.winitzki.jc

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext

class JJoinPool extends JPoolExecutor(1)
class JProcessPool(threads: Int) extends JPoolExecutor(threads)

/*
class JThreadPoolExecutor(threads: Int = 1) extends JThreadPool {
  val execContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(threads))

  def shutdownNow() = ()

  def runTask(task: JThreadPool => Unit) = {
    Future { task(JThreadPoolExecutor.this)}(execContext)
  }
}
*/
/* */
class JPoolExecutor(threads: Int = 8) extends JPool {
  val execService = Executors.newFixedThreadPool(threads)

  val sleepTime = 100

  def shutdownNow() = new Thread {
    execService.shutdown()
    Thread.sleep(sleepTime)
    execService.shutdownNow()
  }

  def runProcess(task: => Unit): Unit = execService.execute(new Runnable {
    override def run(): Unit = task
  })
}

/* */