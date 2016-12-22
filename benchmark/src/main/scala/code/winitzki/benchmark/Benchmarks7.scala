package code.winitzki.benchmark

import java.time.LocalDateTime
import code.winitzki.benchmark.Common._
import code.winitzki.jc._
import code.winitzki.jc.Macros._

object Benchmarks7 {

  /// Concurrent decrement of `n` counters, each going from `count` to 0 concurrently.

  /// create `n` asynchronous counters, initialize each to `count`, then decrement `count*n` times, until all counters are zero.
  /// collect the zero-counter events, make sure there are `n` of them, then fire an `all_done` event that yields the benchmark time.
  val numberOfCounters = 5

  def benchmark7(count: Int, tp: Pool): Long = {

    val done = m[Unit]
    val all_done = m[Int]
    val f = b[LocalDateTime,Long]

    site(tp)(
      go { case all_done(0) + f(tInit, r) => r(elapsed(tInit)) },
      go { case all_done(x) + done(_) if x > 0 => all_done(x-1) }
    )
    val initialTime = LocalDateTime.now
    all_done(numberOfCounters)

    val d = make_counters(done, numberOfCounters, count, tp)
    (1 to (count*numberOfCounters)).foreach{ _ => d() }

    f(initialTime)
  }

  // this deadlocks whenever `count` * `counters` becomes large.
  def benchmark8(count: Int, tp: Pool): Long = {

    println(s"Creating $numberOfCounters concurrent counters, each going from $count to 0")

    object j8 extends Join {
      object done extends AsyName[Unit]
      object all_done extends AsyName[Int]
      object f extends SynName[LocalDateTime, Long]

      join {
        case all_done(0) and f(tInit) =>
          f.reply(elapsed(tInit))
        case all_done(x) and done(_) if x > 0 => all_done(x-1)
      }

    }

    val initialTime = LocalDateTime.now
    j8.all_done(count)
    val d = make_counters8a(j8.done, numberOfCounters, count)
    (1 to (count*numberOfCounters)).foreach{ _ => d(()) }
    j8.f(initialTime)
  }

  private def make_counters(done: E, counters: Int, init: Int, tp: Pool) = {
    val c = m[Int]
    val d = m[Unit]

    site(tp)(
      go { case c(0) => done() },
      go { case c(n) + d(_) if n > 0 => c(n - 1) }
    )
    (1 to counters).foreach(_ => c(init))
    // We return just one molecule.
    d
  }

  private def make_counters8a(done: AsyName[Unit], counters: Int, init: Int): AsyName[Unit] = {
    object j8a extends Join {
      object c extends AsyName[Int]
      object d extends AsyName[Unit]

      join {
        case c(0) => done(())
        case c(n) and d(_) if n > 0 => c(n-1)
      }

    }
    (1 to counters).foreach(_ => j8a.c(init))
    // We return just one molecule.
    j8a.d
  }

}