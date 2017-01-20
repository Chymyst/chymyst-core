package code.chymyst

import code.chymyst.jc._
import code.chymyst.Chymyst._
import code.chymyst.jc.Core.emptyReactionInfo
import org.scalactic.source.Position
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.concurrent.Waiters.{PatienceConfig, Waiter}
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ChymystSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(2000, Millis)

  val warmupTimeMs = 50L

  val patienceConfig = PatienceConfig(timeout = Span(1000, Millis))

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "future + molecule"

  it should "emit a molecule from a future computed out of a given future" in {

    val c = m[Unit]
    val f = b[Unit, Unit]

    val tp = new FixedPool(2)
    site(tp)(
      go { case c(_) + f(_, r) => r() }
    )

    Future { Thread.sleep(50) } & c    // insert a molecule from the end of the future

    f() shouldEqual (())
    tp.shutdownNow()
  }

  it should "emit a molecule from a future with a lazy emission" in {
    val waiter = new Waiter

    val c = new M[String]("c")
    val tp = new FixedPool(2)

    site(tp)(
      go { case c(x) => waiter { x shouldEqual "send it off"; () }; waiter.dismiss() }
    )

    Future { Thread.sleep(50) } + c("send it off")    // insert a molecule from the end of the future

    waiter.await()(patienceConfig, implicitly[Position])

    tp.shutdownNow()
  }

  it should "not emit a molecule from a future prematurely" in {
    val waiter = new Waiter

    val c = m[Unit]
    val d = m[Unit]
    val e = m[Unit]
    val f = b[Unit, String]
    val f2 = b[Unit, String]

    val tp = new FixedPool(4)
    site(tp)(
      go { case e(_) + c(_) => d() },
      go { case c(_) + f(_, r) => r("from c"); c() },
      go { case d(_) + f2(_, r) => r("from d") }
    )

    c()

    val givenFuture = Future {
      Thread.sleep(20)
    } // waiter has 150 ms timeout

    (givenFuture + e()).map { _ => waiter.dismiss() }
    // The test would fail if e() were emitted right away at this point.

    f() shouldEqual "from c"
    waiter.await()
    f2() shouldEqual "from d"

    tp.shutdownNow()
  }

  behavior of "#moleculeFuture"

  it should "create a future that succeeds when molecule is emitted" in {
    val waiter = new Waiter
    val tp = new FixedPool(4)

    val b = m[Unit]

    // "fut" will succeed when "c" is emitted
    val (c, fut) = moleculeFuture[String](tp)

    site(tp)(
      go { case b(_) => c("send it off") }
    )

    for {
      s <- fut
    } yield {
      waiter {
        s shouldEqual "send it off"; ()
      }
      waiter.dismiss()
    }

    b()
    waiter.await()(patienceConfig, implicitly[Position])

    tp.shutdownNow()
  }

  behavior of "cleanup with resource"

  it should "catch exceptions and not fail" in {
    val tryX = cleanup(1)(_ => throw new Exception("ignore this exception"))(_ => throw new Exception("foo"))
    tryX.isFailure shouldEqual true
  }

  behavior of "withPool"

  def checkPool(tp: Pool): Unit = {
    val waiter = new Waiter

    tp.runClosure({
      val threadInfoOptOpt: Option[Option[ReactionInfo]] = Thread.currentThread match {
        case t : ThreadWithInfo => Some(t.reactionInfo)
        case _ => None
      }
      waiter {
        threadInfoOptOpt shouldEqual Some(Some(emptyReactionInfo))
        ()
      }
      waiter.dismiss()

    }, emptyReactionInfo)

    waiter.await()(patienceConfig, implicitly[Position])
  }

  it should "run tasks on a thread with info, in fixed pool" in {
    withPool(new FixedPool(2))(checkPool).get shouldEqual (())
  }

  it should "run tasks on a thread with info, in smart pool" in {
    withPool(new SmartPool(2))(checkPool).get shouldEqual (())
  }

  it should "run reactions on a thread with reaction info" in {
    val res = withPool(new FixedPool(2)) { tp =>
      val a = m[Unit]
      val result = b[Unit, Option[ReactionInfo]]

      site(tp)(
        go { case a(_) + result(_, r) =>
          val reactionInfo = Thread.currentThread match {
            case t: ThreadWithInfo => t.reactionInfo
            case _ => None
          }
          r(reactionInfo)
        }
      )

      a()
      result()
    }.get
    println(res)
    res should matchPattern {
      case Some(ReactionInfo(Array(InputMoleculeInfo(_, _, Wildcard, _), InputMoleculeInfo(_, _, Wildcard, _)), Array(), AllMatchersAreTrivial, _)) =>
    }
  }

}
