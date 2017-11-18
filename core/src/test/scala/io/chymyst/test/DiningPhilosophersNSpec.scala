package io.chymyst.test

import io.chymyst.jc._

class DiningPhilosophersNSpec extends LogSpec {

  behavior of "n dining philosophers"

  val cycles = 50

  val philosophers = 6

  it should s"run $cycles cycles for $philosophers philosophers without deadlock" in {
    diningPhilosophersN(cycles)
  }

  def randomWait(message: String): Unit = {
    Thread.sleep(math.floor(scala.util.Random.nextDouble * 20.0 + 2.0).toLong)
  }

  def eat(philosopher: Int): Unit = {
    randomWait(s"philosopher $philosopher is eating")
  }

  def think(philosopher: Int): Unit = {
    randomWait(s"philosopher $philosopher is thinking")
  }

  def diningPhilosophersN(cycles: Int): Unit = {
    val tp = FixedPool(8)

    val hungryPhilosophers = Seq.tabulate(philosophers)(i ⇒ new M[Int](s"hungry $i"))
    val thinkingPhilosophers = Seq.tabulate(philosophers)(i ⇒ new M[Int](s"thinking $i"))
    val forks = Seq.tabulate(philosophers)(i ⇒ new M[Unit](s"right fork of $i"))

    val done = m[Unit]
    val check = b[Unit, Unit]

    val reactions: Seq[Reaction] = Seq.tabulate(philosophers) { i ⇒
      val thinking = thinkingPhilosophers(i)
      val hungry = hungryPhilosophers(i)
      val leftFork = forks((i + 1) % philosophers)
      val rightFork = forks(i)
      Seq(
        go { case thinking(n) ⇒ think(i); hungry(n - 1) },
        go { case hungry(n) + leftFork(()) + rightFork(()) ⇒
          eat(i) + thinking(n) + leftFork() + rightFork()
          if (n == 0) done()
        }
      )
    }.flatten

    site(tp)(
      go { case done(()) + check((), reply) => reply() }
    )

    site(tp)(reactions: _*)

    Seq.tabulate(philosophers) { i ⇒
      thinkingPhilosophers(i)(cycles)
      forks(i)()
    }

    check() shouldEqual (())

    tp.shutdownNow()
  }
}
