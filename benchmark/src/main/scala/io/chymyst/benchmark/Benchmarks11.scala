package io.chymyst.benchmark

import io.chymyst.jc._
import Common._
import MergeSort._

object Benchmarks11 {
  val counterMultiplier = 5

  def benchmark11(count: Int, tp: Pool): Long = timeThis {
    (1 to counterMultiplier).foreach { _ ⇒
      val c = m[(Int, Int)]
      val done = m[Int]
      val f = b[Unit, Int]

      val total = count

      site(tp)(
        go { case f(_, r) + done(x) => r(x) },
        go { case c((n, x)) + c((m, y)) if x <= y =>
          val p = n + m
          val z = x + y
          if (p == total)
            done(z)
          else
            c((n + m, x + y))
        }
      )

      (1 to total).foreach(i => c((1, i * i)))
      f()
    }
  }

  val mergeSortSize = 1000
  val mergeSortIterations = 20

  def benchmark12(count: Int, tp: Pool): Long = timeThis {

    (1 to mergeSortIterations).foreach { _ ⇒
      val arr = Array.fill[Int](mergeSortSize)(scala.util.Random.nextInt(mergeSortSize))
      performMergeSort(arr, 8)
    }
  }

}
