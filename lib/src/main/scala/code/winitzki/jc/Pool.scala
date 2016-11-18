package code.winitzki.jc


import java.util.concurrent.{Executors, ExecutorService, SynchronousQueue, ThreadPoolExecutor, TimeUnit}

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.dispatch.Dispatchers
import akka.routing.{BalancingPool, Broadcast, RoundRobinPool, SmallestMailboxPool}
import code.winitzki.jc.JoinRun.{Reaction, ReactionBody}

import scala.concurrent.ExecutionContext

//class JoinPool extends ActorExecutor(2)
//class ReactionPool(threads: Int) extends ActorExecutor(threads)
class JoinPool extends FixedPoolExecutor(2)
class ReactionPool(threads: Int) extends FixedPoolExecutor(threads)

class CachedReactionPool(threads: Int) extends PoolExecutor(threads,
  t => new ThreadPoolExecutor(t, t, 1L, TimeUnit.SECONDS, new SynchronousQueue[Runnable])
)

private[jc] class FixedPoolExecutor(threads: Int) extends PoolExecutor(threads, Executors.newFixedThreadPool)

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
// A pool of execution threads, or another way of running tasks (could use actors or whatever else).
trait Pool {
  def shutdownNow(): Unit

  def runClosure(closure: => Unit): Unit

  def apply(r: ReactionBody): Reaction = Reaction(r, this)
}

private[jc] class WorkerActor extends Actor {


  def receive = {
    case _:Unit => {
      Thread.currentThread().interrupt()
      context.stop(self)
    }
    case task: Runnable => {
//      println(s"Debug: JActor starting task $task")
      task.run()
    }
  }
}

private[jc] class ActorExecutor(threads: Int = 8) extends Pool {

  val actorSystem = ActorSystem("ActorSystemForPoolExecutor")
  val router = actorSystem.actorOf(SmallestMailboxPool(threads).props(Props[WorkerActor]), name = "workerRouter")
//  val router = actorSystem.actorOf(Props[JActor].withRouter(SmallestMailboxPool(threads)), name = "workerRouter")

  override def shutdownNow(): Unit = {
    router ! Broadcast(())
    router ! PoisonPill
    actorSystem.terminate()
  }

  override def runClosure(closure: => Unit): Unit =
    router ! new Runnable {
      override def run(): Unit = closure
    }

}

private[jc] class PoolExecutor(threads: Int = 8, execFactory: Int => ExecutorService) extends Pool {
  private val execService = execFactory(threads)

  val sleepTime = 200

  def shutdownNow() = new Thread {
    execService.shutdown()
    execService.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
    execService.shutdownNow()
    execService.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
    execService.shutdownNow()
  }

  def runClosure(closure: => Unit): Unit = execService.execute(new Runnable {
    override def run(): Unit = closure
  })
}
