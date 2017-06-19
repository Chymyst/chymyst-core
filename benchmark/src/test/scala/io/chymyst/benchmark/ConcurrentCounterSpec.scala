package io.chymyst.benchmark

import org.scalatest.{FlatSpec, Matchers}
import io.chymyst.jc._
import io.chymyst.test.Common._
import ammonite.ops._
import io.chymyst.test.LogSpec

class ConcurrentCounterSpec extends LogSpec {

  val count = 50000

  def runBenchmark(count: Int, threads: Int, verbose: Boolean, writeFile: Boolean = false): Unit = {
    val c = m[Int]
    val g = b[Unit, Int]
    val i = m[Unit]
    val d = m[Unit]
    val f = b[Long, Long]

    val memoryLogger = new MemoryLogger
    val elapsed = withPool(FixedPool(threads).withReporter(if (verbose) new DebugAllReporter(memoryLogger) else ConsoleEmptyReporter)) { tp ⇒

      site(tp)(
        go { case c(0) + f(tInit, r) ⇒
          val x = System.nanoTime()
          r(x - tInit)
        },
        go { case g(_, reply) + c(n) ⇒ c(n); reply(n) },
        go { case c(n) + i(_) ⇒ c(n + 1) },
        go { case c(n) + d(x) if n > 0 ⇒ c(n - 1) }
      )
      c(count)
      val initialTime = System.nanoTime()
      (1 to count).foreach { _ => d() }
      f(initialTime)
    }.get
    println(s"benchmark with $count reactions on $threads threads, with${if(verbose) " " + memoryLogger.messages.size else " no"} logs took ${formatNanosToMs(elapsed)}")
    if (writeFile) write.over(pwd / 'logs / s"benchmark_$count-on-$threads-threads_${if(verbose) "" else "no-"}logging.txt", memoryLogger.messages.map(_ + "\n"))
  }

  def runPipelinedBenchmark(count: Int, threads: Int, pipelined: Boolean, verbose: Boolean, writeFile: Boolean = false): Unit = {
    val c = m[Int]
    val d = m[Int]
    val i = m[Unit]
    val done = m[Unit]
    val f = b[Long, Long]

    val memoryLogger = new MemoryLogger
    val elapsed = withPool(FixedPool(threads).withReporter(if (verbose) new DebugAllReporter(memoryLogger) else ConsoleEmptyReporter)) { tp ⇒
      site(tp)(
        go { case done(_) + f(tInit, r) ⇒
          val x = System.nanoTime()
          r(x - tInit)
        },
        go { case c(n) + i(_) ⇒ c(n + 1) },
        if (pipelined) go { case c(n) + d(x) ⇒ if (n > 1) c(n - 1) else done() } else go { case c(n) + d(x) if n + x > -1 ⇒ if (n > 1) c(n - 1) else done() }
      )
      val initialTime = System.nanoTime()
      c(count)
      (1 to count).foreach(d)
      f(initialTime)
    }.get

    println(s"${if(pipelined) "" else "non-"}pipelined benchmark with $count reactions on $threads threads, with${if(verbose) " " + memoryLogger.messages.size else " no"} logs took ${formatNanosToMs(elapsed)}")
    if (writeFile) write.over(pwd / 'logs / s"${if(pipelined) "" else "non-"}pipelined benchmark_$count-on-$threads-threads_${if(verbose) "" else "no-"}logging.txt", memoryLogger.messages.map(_ + "\n"))
  }

  behavior of "concurrent counter"

  it should s"produce no-logging benchmark1 data on $count counter runs with 8 threads" in {
    runBenchmark(count, 8, verbose = false)
  }

  it should s"produce logging benchmark1 data on $count counter runs with 8 threads" in {
    runBenchmark(count, 8, verbose = true)
  }

  it should s"produce no-logging pipelined benchmark1 data on $count counter runs with 8 threads" in {
    runPipelinedBenchmark(count, 8, pipelined = true, verbose = false)
  }

  // This is extremely slow due to quadratic time while printing log messages: the pipelined queue needs to be traversed each time!
//  it should s"produce logging pipelined benchmark1 data on $count counter runs with 8 threads" in {
//    runPipelinedBenchmark(count, 8, pipelined = true, verbose = true)
//  }

  it should s"produce no-logging non-pipelined benchmark1 data on $count counter runs with 8 threads" in {
    runPipelinedBenchmark(count, 8, pipelined = false, verbose = false)
  }

  it should s"produce no-logging benchmark1 data on $count counter runs with 1 threads" in {
    runBenchmark(count, 1, verbose = false)
  }

  it should s"produce no-logging pipelined benchmark1 data on $count counter runs with 1 threads" in {
    runPipelinedBenchmark(count, 1, pipelined = true, verbose = false)
  }

  it should s"produce no-logging non-pipelined benchmark1 data on $count counter runs with 1 threads" in {
    runPipelinedBenchmark(count, 1, pipelined = false, verbose = false)
  }
}
