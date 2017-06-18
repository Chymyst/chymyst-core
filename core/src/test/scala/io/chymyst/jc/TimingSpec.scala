package io.chymyst.jc

import io.chymyst.test.Common.formatNanosToMs
import org.scalatest.{FlatSpec, Matchers}

class TimingSpec extends FlatSpec with Matchers {

  val count = 10000

  behavior of "timings"

  it should "produce no-logging benchmark1 data" in {
    val c = m[Int]
    val g = b[Unit, Int]
    val i = m[Unit]
    val d = m[Unit]
    val f = b[Long, Long]

    val elapsed = withPool(FixedPool(8)) { tp ⇒
      site(tp)(
        go { case c(0) + f(tInit, r) =>
          val x = System.nanoTime()
          r(x - tInit)
        },
        go { case g(_, reply) + c(n) => c(n); reply(n) },
        go { case c(n) + i(_) => c(n + 1) },
        go { case c(n) + d(_) if n > 0 => c(n - 1) }
      )

      c(count)
      val initialTime = System.nanoTime()
      (1 to count).foreach { _ => d() }
      f(initialTime)
    }.get
    println(s"benchmark1 without logging took ${formatNanosToMs(elapsed)}")
  }

  it should "produce detailed benchmark1 data" in {
    val c = m[Int]
    val g = b[Unit, Int]
    val i = m[Unit]
    val d = m[Unit]
    val f = b[Long, Long]

    var debugString: String = ""

    val elapsed = withPool(FixedPool(8)) { tp ⇒
      site(tp)(
        go { case c(0) + f(tInit, r) =>
          val x = System.nanoTime()
          r(x - tInit)
        },
        go { case g(_, reply) + c(n) => c(n); reply(n) },
        go { case c(n) + i(_) => c(n + 1) },
        go { case c(n) + d(_) if n > 0 ⇒
          val doTimings = n > count / 2 && n <= 100 + count / 2
          if (doTimings) {
            c.timingHelper.update(50)
            debugString += s"\n\n   Iteration: $n\n\n" + c.timingHelper.printAll()
            c.timingHelper.reset()
          }
          c(n - 1)
          if (doTimings)
            c.timingHelper.update(51)
          else c.timingHelper.reset()
        }
      )
      c.timingHelper.reset()
      c(count)
      val initialTime = System.nanoTime()
      (1 to count).foreach { _ => d() }
      f(initialTime)
    }.get
    println(s"benchmark1 with detailed timings took ${formatNanosToMs(elapsed)}")
    scala.tools.nsc.io.File("logs/timings-benchmark-1.txt").writeAll(debugString)
  }

}
