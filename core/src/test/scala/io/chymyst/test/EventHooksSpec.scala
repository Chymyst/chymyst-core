package io.chymyst.test

import io.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class EventHooksSpec extends FlatSpec with Matchers {

  behavior of "whenEmitted"

  it should "resolve future when some molecule is emitted" in {
    val a = m[Int]
    val c = m[Int]
    site(
      go { case a(x) + c(_) ⇒ c(x) }
    )
    (1 to 10).foreach(a)
    c(0)
    val fut = c.whenEmitted
    val res = Await.result(fut, 2.seconds)
    res shouldEqual 1
  }

  behavior of "whenDecided"

  it should "resolve when some reaction is scheduled" in {
    val res = (1 to 20).map { _ =>
      val a = m[Int]
      val c = m[Int]
      withPool(FixedPool(2)) { tp ⇒
        site(tp)(
          go { case a(x) + c(_) ⇒ c(x) }
        )
        val fut = a.whenDecided
        c(0)
        a(1)
        Try(Await.result(fut, 2.seconds)) match {
          case Failure(exception) ⇒ "none"
          case Success(value) ⇒ value
        }
      }.get
    }.toSet
    println(res)
    res should contain("c")
  }

  it should "sometimes fail to resolve when reaction scheduler finishes too early" in {
    val res = (1 to 20).map { _ =>
      val a = m[Int]
      val c = m[Int]
      withPool(FixedPool(2)) { tp ⇒
        site(tp)(
          go { case a(x) + c(_) ⇒ c(x) }
        )
        val fut = c.whenDecided
        c(0)
        a(1)
        Try(Await.result(fut, 2.seconds)) match {
          case Failure(exception) ⇒ "none"
          case Success(value) ⇒ value
        }
      }.get
    }.toSet
    println(res)
    res should contain("c")
  }

  it should "resolve when another reaction is scheduled" in {
    val res = (1 to 20).map { _ =>
      val a = m[Int]
      val c = m[Int]
      withPool(FixedPool(2)) { tp ⇒
        site(tp)(
          go { case a(x) + c(_) ⇒ c(x) }
        )
        val fut = c.whenDecided
        a(1)
        c(0)
        Try(Await.result(fut, 2.seconds)) match {
          case Failure(_) ⇒ "none"
          case Success(v) ⇒ v
        }
      }.get
    }.toSet
    println(res)
    res should contain("a")
  }

  it should "fail to resolve when no reaction can be scheduled" in {
    val a = m[Int]
    val c = m[Int]
    withPool(FixedPool(2)) { tp ⇒
      site(tp)(
        go { case a(x) + c(_) ⇒ c(x) }
      )
      val fut = a.whenDecided
      a(1)
      the[Exception] thrownBy Await.result(fut, 2.seconds) should have message "a.whenDecided() failed because no reaction could be scheduled (this is not an error)"
    }.get

  }

  it should "be an automatic failure when used on reaction threads" in {
    val a = m[Int]
    val c = b[Unit, Future[String]]
    withPool(FixedPool(2)) { tp ⇒
      site(tp)(
        go { case a(x) + c(_, r) ⇒ r(a.whenDecided) }
      )
      a(1)
      val fut = c()
      fut.isCompleted shouldEqual  true
      the[Exception] thrownBy Await.result(fut, 2.seconds) should have message "whenDecided() is disallowed on reaction threads (molecule: a)"
    }.get
  }

  behavior of "whenConsumed"

  it should "resolve when a molecule is consumed by reaction" in {
    val a = m[Unit]
    withPool(FixedPool(2)) { tp ⇒
      site(tp)(
        go { case a(x) ⇒ }
      )
      val fut = a.emitUntilConsumed()
      Await.result(fut, 2.seconds) shouldEqual (())
    }.get
  }

  it should "time out when another copy of molecule is consumed" in {
    val a = m[Int]
    val f = b[Unit, Unit]
    withPool(FixedPool(2)) { tp ⇒
      site(tp)(
        go { case a(x) + f(_, r) ⇒ r() }
      )
      a.isPipelined shouldEqual true
      val fut0 = a.emitUntilConsumed(10)
      val fut = a.emitUntilConsumed(123)
      f()
      fut0.isCompleted shouldEqual true
      the[Exception] thrownBy Await.result(fut, 500.millis) should have message "Futures timed out after [500 milliseconds]"
      Await.result(fut0, 500.millis) shouldEqual 10
    }.get

  }

  it should "be an automatic failure when used on reaction threads" in {
    val a = m[Int]
    val c = b[Unit, Future[Int]]
    withPool(FixedPool(2)) { tp ⇒
      site(tp)(
        go { case a(x) + c(_, r) ⇒ r(a.emitUntilConsumed(123)) }
      )
      a(1)
      val fut = c()
      fut.isCompleted shouldEqual  true
      the[Exception] thrownBy Await.result(fut, 2.seconds) should have message "emitUntilConsumed() is disallowed on reaction threads (molecule: a)"
    }.get
  }
}
