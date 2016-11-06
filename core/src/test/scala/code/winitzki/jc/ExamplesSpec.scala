package code.winitzki.jc

import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import JoinRun._

class ExamplesSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(10000, Millis)

  def rw(m: JChan): Unit = {
    println(m.toString)
    Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)
  }

  it should "run 5 dining philosophers for 50 cycles without deadlock" in {
    val h1 = ja[Int]("Aristotle is hungry")
    val h2 = ja[Int]("Kant is hungry")
    val h3 = ja[Int]("Marx is hungry")
    val h4 = ja[Int]("Russell is hungry")
    val h5 = ja[Int]("Spinoza is hungry")
    val t1 = ja[Int]("Aristotle is thinking")
    val t2 = ja[Int]("Kant is thinking")
    val t3 = ja[Int]("Marx is thinking")
    val t4 = ja[Int]("Russell is thinking")
    val t5 = ja[Int]("Spinoza is thinking")
    val f12 = ja[Unit]("f12")
    val f23 = ja[Unit]("f23")
    val f34 = ja[Unit]("f34")
    val f45 = ja[Unit]("f45")
    val f51 = ja[Unit]("f51")

    val done = ja[Unit]("done")
    val check = js[Unit, Unit]("check")

    val cycles = 50

    join (
      & { case t1(n) => rw(h1); h1(n - 1) },
      & { case t2(n) => rw(h2); h2(n - 1) },
      & { case t3(n) => rw(h3); h3(n - 1) },
      & { case t4(n) => rw(h4); h4(n - 1) },
      & { case t5(n) => rw(h5); h5(n - 1) },

      & { case done(_) + check(_, r) => r() },

      & { case h1(n) + f12(_) + f51(_) => rw(t1); t1(n) + f12() + f51(); if (n == 0) done() },
      & { case h2(n) + f23(_) + f12(_) => rw(t2); t2(n) + f23() + f12() },
      & { case h3(n) + f34(_) + f23(_) => rw(t3); t3(n) + f34() + f23() },
      & { case h4(n) + f45(_) + f34(_) => rw(t4); t4(n) + f45() + f34() },
      & { case h5(n) + f51(_) + f45(_) => rw(t5); t5(n) + f51() + f45() }
    )
    t1(cycles) + t2(cycles) + t3(cycles) + t4(cycles) + t5(cycles)
    f12() + f23() + f34() + f45() + f51()

    check() shouldEqual ()
  }

  it should "sort an array using concurrent merge sort quickly" in {

  }
}
