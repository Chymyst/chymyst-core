package code.winitzki.benchmark

import code.winitzki.benchmark.Common._
import code.winitzki.jc.ReactionPool
import code.winitzki.jc.JoinRun._
import org.scalatest.{FlatSpec, Matchers}

class MultithreadSpec extends FlatSpec with Matchers {

  it should "run tasks on many threads much faster than on one thread" in {

    def runWork(threads: Int) = {

      def performWork(): Unit = {
        val n = 100

        (1 to n).foreach(i => (1 to i).foreach(j => (1 to j).foreach(k => math.cos(10000.0))))
      }

      val a = m[Int]
      val never = b[Unit, Unit]
      val tp = new ReactionPool(threads)
      join(
        tp { case a(c) if c > 0 => performWork(); a(c - 1) },
        tp { case never(_, r) + a(0) => r() }
      )

      (1 to 10).foreach(_ => a(10))
      never()
    }

    val result8 = timeWithPriming{runWork(8)}
    val result1 = timeWithPriming{runWork(1)}

    println(s"with 1 thread $result1 ms, with 8 threads $result8 ms")

    (3 * result8 < result1) shouldEqual true
  }
}
