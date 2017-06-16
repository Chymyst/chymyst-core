package io.chymyst.benchmark

import org.scalatest.{FlatSpec, Matchers}
import io.chymyst.jc._
import io.chymyst.test.Common._

class ConcurrentCounterSpec extends FlatSpec with Matchers {

  val count = 50000

  behavior of "concurrent counter"

  it should s"produce no-logging benchmark1 data on $count counter runs" in {
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

  it should s"produce benchmark1 data on $count counter runs" in {
    val c = m[Int]
    val g = b[Unit, Int]
    val i = m[Unit]
    val d = m[Unit]
    val f = b[Long, Long]

    val memoryLogger = new MemoryLogger
    val elapsed = withPool(FixedPool(8).withReporter(new DebugAllReporter(memoryLogger))) { tp ⇒
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
    println(s"benchmark1 with logging took ${formatNanosToMs(elapsed)}")
  }

  it should s"produce no-logging pipelined benchmark1 data on $count counter runs" in {
    val c = m[Int]
    val d = m[Unit]
    val done = m[Unit]
    val f = b[Long, Long]

    val elapsed = withPool(FixedPool(8)) { tp ⇒
      site(tp)(
        go { case done(_) + f(tInit, r) ⇒
          val x = System.nanoTime()
          r(x - tInit)
        },
        go { case c(n) + d(_) ⇒ if (n > 1) c(n - 1) else done() }
      )

      c(count)
      val initialTime = System.nanoTime()
      (1 to count).foreach { _ => d() }
      f(initialTime)
    }.get

    println(s"pipelined benchmark1 without logging took ${formatNanosToMs(elapsed)}")
  }

  it should s"produce pipelined benchmark1 data on $count counter runs" in {
    val c = m[Int]
    val d = m[Unit]
    val done = m[Unit]
    val f = b[Long, Long]

    val memoryLogger = new MemoryLogger
    val elapsed = withPool(FixedPool(8).withReporter(new DebugAllReporter(memoryLogger))) { tp ⇒
      site(tp)(
        go { case done(_) + f(tInit, r) ⇒
          val x = System.nanoTime()
          r(x - tInit)
        },
        go { case c(n) + d(_) ⇒ if (n > 1) c(n - 1) else done() }
      )

      c(count)
      val initialTime = System.nanoTime()
      (1 to count).foreach { _ => d() }
      f(initialTime)
    }.get

    println(s"pipelined benchmark1 with logging took ${formatNanosToMs(elapsed)}")
  }

}
