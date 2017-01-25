package io.chymyst.jc
/* TODO: Move this to a different project that depends on Akka.
import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.dispatch.Dispatchers
import akka.routing.{BalancingPool, Broadcast, RoundRobinPool, SmallestMailboxPool}

private[jc] class WorkerActor extends Actor {


  def receive = {
    case _:Unit =>
      Thread.currentThread().interrupt()
      context.stop(self)

    case task: Runnable =>
      //      println(s"Debug: JActor starting task $task")
      task.run()

  }
}

private[jc] class ActorPool(threads: Int = 8) extends Pool {

  val actorSystem = ActorSystem("ActorSystemForActorPool")
  val router = actorSystem.actorOf(BalancingPool(threads).props(Props[WorkerActor]), name = "workerRouter")
  //  val router = actorSystem.actorOf(Props[JActor].withRouter(SmallestMailboxPool(threads)), name = "workerRouter")

  override def shutdownNow(): Unit = {
    router ! Broadcast(())
    router ! PoisonPill
    actorSystem.terminate()
  }

  override def runClosure(closure: => Unit, name: Option[String]): Unit =
    router ! new Runnable {
      override def toString: String = name.getOrElse(super.toString)
      override def run(): Unit = closure
    }

  override def isInactive: Boolean = actorSystem.whenTerminated.isCompleted

}
*/
