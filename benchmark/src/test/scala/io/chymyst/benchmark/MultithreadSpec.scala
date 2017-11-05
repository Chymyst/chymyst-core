package io.chymyst.benchmark

import io.chymyst.benchmark.Common._
import io.chymyst.jc._
import io.chymyst.test.LogSpec

class MultithreadSpec extends LogSpec {

  it should "run time-consuming tasks on many threads faster than on one thread" in {

    def runWork(threads: Int) = {

      def performWork(): Unit = {
        // Simulate CPU load.
        Thread.sleep(1000)
      }

      val work = m[Unit]
      val finished = m[Unit]
      val counter = m[Int]
      val allFinished = b[Unit, Unit]
      val tp = FixedPool(threads)
      site(tp)(
        go { case work(_) => performWork(); finished() },
        go { case counter(n) + finished(_) => counter(n-1) },
        go { case allFinished(_, r) + counter(0) => r() }
      )
      val total = 8
      (1 to total).foreach(_ => work())
      counter(total)
      allFinished()
      tp.shutdownNow()
    }

    val result1 = timeWithPriming{runWork(1)}
    val result8 = timeWithPriming{runWork(8)}

    println(s"with 1 thread $result1 ms, with 8 threads $result8 ms")

    withClue("Running Thread.sleep() on 8 threads should be at least 7 times faster than on 1 thread") {
      (result1 / 7) should be > result8
    }
  }
}
