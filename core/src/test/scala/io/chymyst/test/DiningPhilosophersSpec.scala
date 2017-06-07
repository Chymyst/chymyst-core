package io.chymyst.test

import io.chymyst.jc._

class DiningPhilosophersSpec extends LogSpec {

  def randomWait(message: String): Unit = {
    Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)
  }

  def eat(philosopher: Philosopher): Unit = {
    randomWait(s"$philosopher is eating")
  }

  def think(philosopher: Philosopher): Unit = {
    randomWait(s"$philosopher is thinking")
  }

  val cycles: Int = 50
  it should s"run 5 dining philosophers for $cycles cycles without deadlock" in {
    diningPhilosophers(cycles)
  }

  sealed trait Philosopher
  case object Socrates extends Philosopher
  case object Confucius extends Philosopher
  case object Plato extends Philosopher
  case object Descartes extends Philosopher
  case object Voltaire extends Philosopher

  private def diningPhilosophers(cycles: Int) = {

    val tp = FixedPool(8)

    val hungry1 = m[Int] // The `Int` value represents how many cycles of eating/thinking still remain.
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
      go { case thinking1(n) => think(Socrates);  hungry1(n - 1) },
      go { case thinking2(n) => think(Confucius); hungry2(n - 1) },
      go { case thinking3(n) => think(Plato);     hungry3(n - 1) },
      go { case thinking4(n) => think(Descartes); hungry4(n - 1) },
      go { case thinking5(n) => think(Voltaire);  hungry5(n - 1) },

      go { case done(()) + check((), reply) => reply() },

      go { case hungry1(n) + fork12(()) + fork51(()) => eat(Socrates);  thinking1(n) + fork12() + fork51(); if (n == 0) done() },
      go { case hungry2(n) + fork23(()) + fork12(()) => eat(Confucius); thinking2(n) + fork23() + fork12() },
      go { case hungry3(n) + fork34(()) + fork23(()) => eat(Plato);     thinking3(n) + fork34() + fork23() },
      go { case hungry4(n) + fork45(()) + fork34(()) => eat(Descartes); thinking4(n) + fork45() + fork34() },
      go { case hungry5(n) + fork51(()) + fork45(()) => eat(Voltaire);  thinking5(n) + fork51() + fork45() }
    )

    thinking1(cycles) + thinking2(cycles) + thinking3(cycles) + thinking4(cycles) + thinking5(cycles)
    fork12() + fork23() + fork34() + fork45() + fork51()

    check() shouldEqual (())
    tp.shutdownNow()
  }

}
