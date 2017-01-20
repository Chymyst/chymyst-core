package code.chymyst.test

import code.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers}

class DiningPhilosophersSpec extends FlatSpec with Matchers {

  def randomWait(message: String): Unit = {
    println(message)
    Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)
  }

  val philosophers = IndexedSeq("Socrates", "Confucius", "Plato", "Descartes", "Voltaire")

  def eating(philosopher: Int): Unit = {
    randomWait(s"${philosophers(philosopher-1)} is eating")
  }
  def thinking(philosopher: Int): Unit = {
    randomWait(s"${philosophers(philosopher-1)} is thinking")
  }

  val cycles: Int = 50
  it should s"run 5 dining philosophers for $cycles cycles without deadlock" in {
    diningPhilosophers(cycles)
  }

  private def diningPhilosophers(cycles: Int) = {

    val tp = new FixedPool(8)

    val hungry1 = m[Int]
    val hungry2 = m[Int]
    val hungry3 = m[Int]
    val hungry4 = m[Int]
    val hungry5 = m[Int]
    val thinking1 = m[Int]
    val thinking2 = m[Int]
    val thinking3 = m[Int]
    val thinking4 = m[Int]
    val thinking5 = m[Int]
    val fork12 = m[Unit]
    val fork23 = m[Unit]
    val fork34 = m[Unit]
    val fork45 = m[Unit]
    val fork51 = m[Unit]

    val done = m[Unit]
    val check = b[Unit, Unit]

    site(tp) (
      go { case thinking1(n) => thinking(1); hungry1(n - 1) },
      go { case thinking2(n) => thinking(2); hungry2(n - 1) },
      go { case thinking3(n) => thinking(3); hungry3(n - 1) },
      go { case thinking4(n) => thinking(4); hungry4(n - 1) },
      go { case thinking5(n) => thinking(5); hungry5(n - 1) },

      go { case done(_) + check(_, r) => r() },

      go { case hungry1(n) + fork12(_) + fork51(_) => eating(1); thinking1(n) + fork12() + fork51(); if (n == 0) done() },
      go { case hungry2(n) + fork23(_) + fork12(_) => eating(2); thinking2(n) + fork23() + fork12() },
      go { case hungry3(n) + fork34(_) + fork23(_) => eating(3); thinking3(n) + fork34() + fork23() },
      go { case hungry4(n) + fork45(_) + fork34(_) => eating(4); thinking4(n) + fork45() + fork34() },
      go { case hungry5(n) + fork51(_) + fork45(_) => eating(5); thinking5(n) + fork51() + fork45() }
    )

    thinking1(cycles) + thinking2(cycles) + thinking3(cycles) + thinking4(cycles) + thinking5(cycles)
    fork12() + fork23() + fork34() + fork45() + fork51()

    check() shouldEqual (())
    tp.shutdownNow()
  }

}
