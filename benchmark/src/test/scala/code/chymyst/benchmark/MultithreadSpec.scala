package code.chymyst.benchmark

import code.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers}
import Common._

class MultithreadSpec extends FlatSpec with Matchers {

  it should "run tasks on many threads much faster than on one thread" in {

    def runWork(threads: Int) = {

      def performWork(): Unit = {
        val n = 200
        // load the CPU with some work:
        (1 to n).foreach(i => (1 to i).foreach(j => (1 to j).foreach(k => math.cos(10000.0))))
      }


      val work = m[Unit]
      val finished = m[Unit]
      val counter = m[Int]
      val allFinished = b[Unit, Unit]
      val tp = new FixedPool(threads)
      val tp2 = new FixedPool(2)
      site(tp,tp2)(
        go { case work(_) => performWork(); finished() },
        go { case counter(n) + finished(_) => counter(n-1) },
        go { case allFinished(_, r) + counter(0) => r() }
      )
      val total = 8
      (1 to total).foreach(_ => work())
      counter(total)
      allFinished()
      tp.shutdownNow()
      tp2.shutdownNow()
    }

    val result8 = timeWithPriming{runWork(8)}
    val result1 = timeWithPriming{runWork(1)}

    println(s"with 1 thread $result1 ms, with 8 threads $result8 ms")

    (3 * result8 / 2) should be < result1
  }
}
