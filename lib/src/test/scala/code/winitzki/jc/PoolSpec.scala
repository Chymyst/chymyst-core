package code.winitzki.jc

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.concurrent.Waiters.{PatienceConfig, Waiter}
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}
import org.scalactic.source.Position

class PoolSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(1500, Millis)

  val patienceConfig = PatienceConfig(timeout = Span(500, Millis))

  behavior of "thread with info"

  def checkPool(tp: Pool): Unit = {
    val waiter = new Waiter

    tp.runClosure({
      val threadInfoOptOpt: Option[Option[ReactionInfo]] = Thread.currentThread match {
        case t : ThreadWithInfo => Some(t.reactionInfo)
        case _ => None
      }
      waiter { threadInfoOptOpt shouldEqual Some(Some(emptyReactionInfo)) }
      waiter.dismiss()

    }, emptyReactionInfo)

    waiter.await()(patienceConfig, implicitly[Position])
  }

  it should "run tasks on a thread with info, in fixed pool" in {
    Chymyst.withPool(new FixedPool(2))(checkPool).get shouldEqual ()
  }

  it should "run tasks on a thread with info, in cached pool" in {
    Chymyst.withPool(new CachedPool(2))(checkPool).get shouldEqual ()
  }

  it should "run tasks on a thread with info, in smart pool" in {
    Chymyst.withPool(new SmartPool(2))(checkPool).get shouldEqual ()
  }

  it should "run reactions on a thread with reaction info" in {
    Chymyst.withPool(new FixedPool(2)){ tp =>
      val waiter = new Waiter
      val a = new M[Unit]("a")

      site(tp)(
        _go { case a(_) =>
          val reactionInfo = Thread.currentThread match {
            case t: ThreadWithInfo => t.reactionInfo
            case _ => None
          }

          waiter {
            (reactionInfo match {
              case Some(ReactionInfo(List(InputMoleculeInfo(a, UnknownInputPattern, _)), None, GuardPresenceUnknown, _)) => true
              case _ => false
            }) shouldEqual true
          }
          waiter.dismiss()
        }
      )

      a()
      waiter.await()(patienceConfig, implicitly[Position])
    }.get shouldEqual()
  }

  behavior of "fixed thread pool"

  it should "run a task on a separate thread" in {
    val waiter = new Waiter

    val tp = new FixedPool(2)

    tp.runClosure({
      waiter.dismiss()

      try {
        Thread.sleep(10000000)  // this should not time out

      } catch {
        case e: InterruptedException => ()
      }
    }, emptyReactionInfo)

    waiter.await()(patienceConfig, implicitly[Position])

    tp.shutdownNow()
  }

  it should "interrupt a thread when shutting down" in {
    val waiter = new Waiter

    val tp = new FixedPool(2)

    tp.runClosure({
      try {
        Thread.sleep(10000000)  // this should not time out

      } catch {
        case e: InterruptedException => waiter.dismiss()
        case other: Exception =>
          other.printStackTrace()
          waiter { false shouldEqual true }
      }
    }, emptyReactionInfo)
    Thread.sleep(20)

    tp.shutdownNow()

    waiter.await()(patienceConfig, implicitly[Position])
  }

  behavior of "cached thread pool"

  it should "run a task on a separate thread" in {
    val waiter = new Waiter

    val tp = new CachedPool(2)

    tp.runClosure({
      waiter.dismiss()

      try {
        Thread.sleep(10000000)  // this should not time out

      } catch {
        case e: InterruptedException => ()
      }
    }, emptyReactionInfo)

    waiter.await()(patienceConfig, implicitly[Position])

    tp.shutdownNow()
  }

  it should "interrupt a thread when shutting down" in {
    val waiter = new Waiter

    val tp = new CachedPool(2)

    tp.runClosure({
      try {
        Thread.sleep(10000000)  // this should not time out

      } catch {
        case e: InterruptedException => waiter.dismiss()
        case other: Exception =>
          other.printStackTrace()
          waiter.dismiss()
      }
    }, emptyReactionInfo)
    Thread.sleep(20)

    tp.shutdownNow()

    waiter.await()(patienceConfig, implicitly[Position])
  }

  behavior of "smart thread"

  it should "run tasks on ordinary threads" in {
    var x = 0
    new RunnableWithInfo({x = 1}, emptyReactionInfo).run()

    x shouldEqual 1

  }

  it should "run tasks on smart threads and store info" in {
    val waiter = new Waiter

    var x = 0
    val runnable = new RunnableWithInfo({
      x = 1; waiter.dismiss()
    }, emptyReactionInfo)
    val smartThread = new ThreadWithInfo(runnable)
    smartThread.reactionInfo shouldEqual None // too early now, the runnable has not yet started
    smartThread.start()

    waiter.await()(patienceConfig, implicitly[Position])

    x shouldEqual 1
    smartThread.reactionInfo shouldEqual Some(emptyReactionInfo)
  }

}
