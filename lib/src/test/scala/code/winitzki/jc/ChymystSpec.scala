package code.winitzki.jc

import Chymyst._
import Core._
import org.scalactic.source.Position
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.Waiters.{PatienceConfig, Waiter}
import org.scalatest.time.{Millis, Span}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ChymystSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(2000, Millis)

  val warmupTimeMs = 50L

  val patienceConfig = PatienceConfig(timeout = Span(1000, Millis))

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "future + molecule"

  it should "emit a molecule from a future computed out of a given future" in {

    val c = new E("c")
    val f = new EE("f")

    val tp = new FixedPool(2)
    site(tp)(
      _go { case c(_) + f(_, r) => r() }
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
      _go { case c(x) => waiter { x shouldEqual "send it off"; () }; waiter.dismiss() }
    )

    Future { Thread.sleep(50) } + c("send it off")    // insert a molecule from the end of the future

    waiter.await()(patienceConfig, implicitly[Position])

    tp.shutdownNow()
  }

  it should "not emit a molecule from a future prematurely" in {
    val waiter = new Waiter

    val c = new E("c")
    val d = new E("d")
    val e = new E("e")
    val f = new EB[String]("f")
    val f2 = new EB[String]("f2")

    val tp = new FixedPool(4)
    site(tp)(
      _go { case e(_) + c(_) => d() },
      _go { case c(_) + f(_, r) => r("from c"); c() },
      _go { case d(_) + f2(_, r) => r("from d") }
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

    val b = new E("b")

    // "fut" will succeed when "c" is emitted
    val (c, fut) = moleculeFuture[String](tp)

    site(tp)(
      _go { case b(_) => c("send it off") }
    )

    val givenFuture = for {
      _ <- Future {
        Thread.sleep(20)
      } // waiter has 150 ms timeout
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

}