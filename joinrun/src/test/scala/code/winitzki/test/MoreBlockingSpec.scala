package code.winitzki.test

import code.winitzki.jc.FixedPool
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}

class MoreBlockingSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(1500, Millis)


  it should "wait for blocking molecule before injecting non-blocking molecule" in {
    val a = m[Int]
    val f = b[Unit,Int]
    val g = b[Unit,Int]
    val c = m[Int]
    val d = m[Int]

    val tp = new FixedPool(20)

    join(tp,tp)(
      &{ case f(_, r) => r(123) },
      &{ case g(_, r) + a(x) => r(x) },
      &{ case g(_, r) + d(x) => r(-x) },
      &{ case c(x) => val y = f(); if (y>0) d(x) else a(x) }
    )

    c(-2)
    Thread.sleep(50)
    c.logSoup shouldEqual "Join{a + g/B => ...; c => ...; d + g/B => ...; f/B => ...}\nMolecules: d(-2)"
    g() shouldEqual 2
    c.logSoup shouldEqual "Join{a + g/B => ...; c => ...; d + g/B => ...; f/B => ...}\nNo molecules"

    tp.shutdownNow()
  }

}
