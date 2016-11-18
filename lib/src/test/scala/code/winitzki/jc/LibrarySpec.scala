package code.winitzki.jc

import JoinRun._
import Library._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.Waiters.Waiter
import org.scalatest.time.{Millis, Span}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LibrarySpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(500, Millis)

  val warmupTimeMs = 50

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)


  it should "inject a molecule from a future computed out of a given future" in {
    val waiter = new Waiter

    val c = ja[Unit]("c")

    join(
      & { case c(_) => waiter.dismiss() }
    )

    val givenFuture = Future {
      Thread.sleep(100)
    }

    // insert a molecule from the end of the future
    givenFuture.map(_ => c())

    waiter.await()

  }

  it should "not inject a molecule from a future prematurely" in {
    val waiter = new Waiter

    val c = ja[Unit]("c")
    val rmC = ja[Unit]("remove c")
    val d = ja[Unit]("d")
    val f = js[Unit, String]("f")

    join(
      & { case c(_) + rmC(_) => },
      & { case c(_) + f(_, r) => r("from c") },
      & { case d(_) + f(_, r) => r("from d") }
    )

    c()

    val givenFuture = Future {
      Thread.sleep(100)
    } // waiter has 150 ms timeout

    givenFuture.map { _ => rmC() + d(); waiter.dismiss() }
    // The test would fail if rmC() + d() were injected right away at this point.

    waitSome()
    f() shouldEqual "from c"
    waiter.await()
    f() shouldEqual "from d"
  }

  it should "create a future that succeeds when molecule is injected" in {
    val waiter = new Waiter

    val b = ja[Unit]("b")
    // "fut" will succeed when "c" is injected
    val (c, fut) = makeFuture[String]

    join(
      & { case b(_) => c("send it off") }
    )

    val givenFuture = for {
      _ <- Future {
        Thread.sleep(100)
      } // waiter has 150 ms timeout
      s <- fut
    } yield {
      waiter {
        s shouldEqual "send it off"
      }
      waiter.dismiss()
    }

    b()
    waiter.await()
  }

}