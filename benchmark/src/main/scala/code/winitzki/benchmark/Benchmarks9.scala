package code.winitzki.benchmark

import java.time.LocalDateTime
import code.winitzki.benchmark.Common._
import code.winitzki.jc._
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._

object Benchmarks9 {

  val numberOfCounters = 5

  def make_counter_1(done: M[Unit], counters: Int, init: Int, tp: Pool): B[Unit,Unit] = {
    val c = m[Int]
    val d = b[Unit, Unit]

    join(tp)(
      & { case c(0) => done() },
      & { case c(n) + d(_, r) if n > 0 => c(n - 1); r() }
    )
    (1 to counters).foreach(_ => c(init))
    // We return just one molecule.
    d
  }

  // inject a blocking molecule many times
  def benchmark9_1(count: Int, threads: Int = 2): Long = {

    val done = m[Unit]
    val all_done = m[Int]
    val f = b[LocalDateTime,Long]

    val tp = new FixedPool(threads)

    join(
      run { case all_done(0) + f(tInit, r) => r(elapsed(tInit)) },
      run { case all_done(x) + done(_) if x > 0 => all_done(x-1) }
    )
    //    done.setLogLevel(2)
    val initialTime = LocalDateTime.now
    all_done(numberOfCounters)

    val d = make_counter_1(done, numberOfCounters, count, tp)
    (1 to (count*numberOfCounters)).foreach{ _ => d() }

    var result = f(initialTime)
    tp.shutdownNow()
    result
  }


  def make_ping_pong_stack(done: M[Unit], tp: Pool): B[Int,Int] = {
    val c = m[Unit]
    val d = b[Int, Int]
    val e = b[Int, Int]

    join(tp)(
      & { case c(_) + d(n, reply) => if (n > 0) {
        c()
        e(n-1)
      }
      else {
        done()
      }
      reply(n)
      },
      & { case c(_) + e(n, reply) => if (n > 0) {
        c()
        d(n-1)
      }
      else {
        done()
      }
        reply(n-1)
      }
    )
    c()
    // We return just one molecule injector.
    d
  }

  val pingPongCalls = 1000

  // ping-pong-stack with blocking molecules
  def benchmark9_2(count: Int, threads: Int = 2): Long = {

    val done = m[Unit]
    val all_done = m[Int]
    val f = b[LocalDateTime,Long]

    val tp = new SmartPool(threads) // this will not work with a fixed pool

    join(
      run { case all_done(0) + f(tInit, r) => r(elapsed(tInit)) },
      run { case all_done(x) + done(_) if x > 0 => all_done(x-1) }
    )
    //    done.setLogLevel(2)
    val initialTime = LocalDateTime.now
    all_done(1)

    val d = make_ping_pong_stack(done, tp)
    d(pingPongCalls)

    var result = f(initialTime)
    tp.shutdownNow()
    result
  }

}