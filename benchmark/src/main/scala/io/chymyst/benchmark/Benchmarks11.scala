package io.chymyst.benchmark

import io.chymyst.benchmark.Common._
import io.chymyst.jc._


object Benchmarks11 {
  def benchmark11(count: Int, tp: Pool): Long = timeThis {
    val c = m[(Int, Int)]
    val done = m[Int]
    val f = b[Unit, Int]

    val total = count * Benchmarks9.counterMultiplier

    val tp1 = new FixedPool(5)

    site(tp, tp1)(
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
    tp1.shutdownNow()
  }
}
