package code.chymyst.test

import code.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers}

class DiningPhilosophersSpec extends FlatSpec with Matchers {

  def randomWait(m: Molecule): Unit = {
    println(m.toString)
    Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)
  }

  val cycles: Int = 50
  it should s"run 5 dining philosophers for $cycles cycles without deadlock" in {
    diningPhilosophers(cycles)
  }

  private def diningPhilosophers(cycles: Int) = {

    val tp = new FixedPool(8)

    val hungry1 = new M[Int]("Socrates is eating")
    val hungry2 = new M[Int]("Confucius is eating")
    val hungry3 = new M[Int]("Descartes is eating")
    val hungry4 = new M[Int]("Plato is eating")
    val hungry5 = new M[Int]("Voltaire is eating")
    val thinking1 = new M[Int]("Socrates is thinking")
    val thinking2 = new M[Int]("Confucius is thinking")
    val thinking3 = new M[Int]("Descartes is thinking")
    val thinking4 = new M[Int]("Plato is thinking")
    val thinking5 = new M[Int]("Voltaire is thinking")
    val fork12 = new E("fork12")
    val fork23 = new E("fork23")
    val fork34 = new E("fork34")
    val fork45 = new E("fork45")
    val fork51 = new E("fork51")

    val done = new E("done")
    val check = new EE("check")

    site(tp) (
      go { case thinking1(n) => randomWait(hungry1); hungry1(n - 1) },
      go { case thinking2(n) => randomWait(hungry2); hungry2(n - 1) },
      go { case thinking3(n) => randomWait(hungry3); hungry3(n - 1) },
      go { case thinking4(n) => randomWait(hungry4); hungry4(n - 1) },
      go { case thinking5(n) => randomWait(hungry5); hungry5(n - 1) },

      go { case done(_) + check(_, r) => r() },

      go { case hungry1(n) + fork12(_) + fork51(_) => randomWait(thinking1); thinking1(n) + fork12() + fork51(); if (n == 0) done() },
      go { case hungry2(n) + fork23(_) + fork12(_) => randomWait(thinking2); thinking2(n) + fork23() + fork12() },
      go { case hungry3(n) + fork34(_) + fork23(_) => randomWait(thinking3); thinking3(n) + fork34() + fork23() },
      go { case hungry4(n) + fork45(_) + fork34(_) => randomWait(thinking4); thinking4(n) + fork45() + fork34() },
      go { case hungry5(n) + fork51(_) + fork45(_) => randomWait(thinking5); thinking5(n) + fork51() + fork45() }
    )

    thinking1(cycles) + thinking2(cycles) + thinking3(cycles) + thinking4(cycles) + thinking5(cycles)
    fork12() + fork23() + fork34() + fork45() + fork51()

    check() shouldEqual (())
    tp.shutdownNow()
  }

}
