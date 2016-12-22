package code.winitzki.test

import code.winitzki.jc._
import code.winitzki.jc.Macros._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

class DiningPhilosophersSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(10000, Millis)

  def rw(m: Molecule): Unit = {
    println(m.toString)
    Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)
  }

  it should "run 5 dining philosophers for 50 cycles without deadlock" in {
    diningPhilosophers(50)
  }

  private def diningPhilosophers(cycles: Int) = {

    val tp = new FixedPool(8)

    val h1 = new M[Int]("Aristotle is eating")
    val h2 = new M[Int]("Kant is eating")
    val h3 = new M[Int]("Marx is eating")
    val h4 = new M[Int]("Russell is eating")
    val h5 = new M[Int]("Spinoza is eating")
    val t1 = new M[Int]("Aristotle is thinking")
    val t2 = new M[Int]("Kant is thinking")
    val t3 = new M[Int]("Marx is thinking")
    val t4 = new M[Int]("Russell is thinking")
    val t5 = new M[Int]("Spinoza is thinking")
    val f12 = new E("f12")
    val f23 = new E("f23")
    val f34 = new E("f34")
    val f45 = new E("f45")
    val f51 = new E("f51")

    val done = new E("done")
    val check = new EE("check")

    site(tp, tp) (
      go { case t1(n) => rw(h1); h1(n - 1) },
      go { case t2(n) => rw(h2); h2(n - 1) },
      go { case t3(n) => rw(h3); h3(n - 1) },
      go { case t4(n) => rw(h4); h4(n - 1) },
      go { case t5(n) => rw(h5); h5(n - 1) },

      go { case done(_) + check(_, r) => r() },

      go { case h1(n) + f12(_) + f51(_) => rw(t1); t1(n) + f12() + f51(); if (n == 0) done() },
      go { case h2(n) + f23(_) + f12(_) => rw(t2); t2(n) + f23() + f12() },
      go { case h3(n) + f34(_) + f23(_) => rw(t3); t3(n) + f34() + f23() },
      go { case h4(n) + f45(_) + f34(_) => rw(t4); t4(n) + f45() + f34() },
      go { case h5(n) + f51(_) + f45(_) => rw(t5); t5(n) + f51() + f45() }
    )

    t1(cycles) + t2(cycles) + t3(cycles) + t4(cycles) + t5(cycles)
    f12() + f23() + f34() + f45() + f51()

    check() shouldEqual (())

    // stop the simulation: this should be in unit tests, not here
    // not yet implemented
/*
    val stop = m[Unit]
    val wait_for_stop = b[Unit,Unit]
    site( go { case stop(_) + wait_for_stop(_,r) => r() } )
    wait_until_quiet(t1, stop)
    wait_for_stop()
*/
    tp.shutdownNow()
  }

}
