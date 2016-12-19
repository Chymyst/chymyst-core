package code.winitzki.jc

import code.winitzki.jc.JoinRun.{AbsMolValue, InjectionInfo, Molecule, ReactionOrInjectionInfo}
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.concurrent.Waiters.{PatienceConfig, Waiter}
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}
import org.scalactic.source.Position

class PoolSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(1500, Millis)

  val patienceConfig = PatienceConfig(timeout = Span(500, Millis))

  val dummyInfo = InjectionInfo(new MutableBag[Molecule, AbsMolValue[_]])

  behavior of "thread with info"

  def checkPool(tp: Pool): Unit = {
    val waiter = new Waiter

    tp.runClosure({
      val threadInfoOptOpt: Option[Option[ReactionOrInjectionInfo]] = Thread.currentThread match {
        case t : ThreadWithInfo => Some(t.runnableInfo)
        case _ => None
      }
      waiter { threadInfoOptOpt shouldEqual Some(Some(dummyInfo)) }
      waiter.dismiss()

    }, dummyInfo)

    waiter.await()(patienceConfig, implicitly[Position])
  }

  it should "run tasks on a thread with info, in fixed pool" in {
    Library.withPool(new FixedPool(2))(checkPool).get shouldEqual ()
  }

  it should "run tasks on a thread with info, in cached pool" in {
    Library.withPool(new CachedPool(2))(checkPool).get shouldEqual ()
  }

  it should "run tasks on a thread with info, in smart pool" in {
    Library.withPool(new SmartPool(2))(checkPool).get shouldEqual ()
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
    }, dummyInfo)

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
    }, dummyInfo)
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
    }, dummyInfo)

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
    }, dummyInfo)
    Thread.sleep(20)

    tp.shutdownNow()

    waiter.await()(patienceConfig, implicitly[Position])
  }

}
