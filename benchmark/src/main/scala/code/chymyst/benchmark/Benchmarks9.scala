package code.chymyst.benchmark

import java.time.LocalDateTime
import code.chymyst.benchmark.Common._
import code.chymyst.jc._
import scala.concurrent.duration._

import code.jiansen.scalajoin._ // Use precompiled classes from Jiansen's Join.scala, which are in that package.

object Benchmarks9 {

  val numberOfCounters = 5

  def make_counter_1(done: M[Unit], counters: Int, init: Int, tp: Pool): EE = {
    val c = m[Int]
    val d = b[Unit, Unit]

    site(tp)(
      go { case c(0) => done(()) },
      go { case c(n) + d(_, r) if n > 0 => c(n - 1); r() }
    )
    (1 to counters).foreach(_ => c(init))
    // We return just one molecule.
    d
  }

  // emit a blocking molecule many times
  def benchmark9_1(count: Int, tp: Pool): Long = {

    val done = m[Unit]
    val all_done = m[Int]
    val f = b[LocalDateTime,Long]


    site(tp)(
      go { case all_done(0) + f(tInit, r) => r(elapsed(tInit)) },
      go { case all_done(x) + done(_) if x > 0 => all_done(x-1) }
    )
    //    done.setLogLevel(2)
    val initialTime = LocalDateTime.now
    all_done(numberOfCounters)

    val d = make_counter_1(done, numberOfCounters, count, tp)
    (1 to (count*numberOfCounters)).foreach{ _ => d() }

    f(initialTime)
  }


  def make_ping_pong_stack(done: E, tp: Pool): B[Int,Int] = {
    val c = m[Unit]
    val d = b[Int, Int]
    val e = b[Int, Int]

    site(tp)(
      go { case c(_) + d(n, reply) => if (n > 0) {
        c()
        e(n-1)
      }
      else {
        done()
      }
      reply(n)
      },
      go { case c(_) + e(n, reply) => if (n > 0) {
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
    // We return just one molecule emitter.
    d
  }

  val pingPongCalls = 1000

  // ping-pong-stack with blocking molecules
  def benchmark9_2(count: Int, tp: Pool): Long = {

    val done = m[Unit]
    val all_done = m[Int]
    val f = b[LocalDateTime,Long]

    val tp = new SmartPool(MainAppConfig.threads) // this benchmark will not work with a fixed pool

    site(tp)(
      go { case all_done(0) + f(tInit, r) => r(elapsed(tInit)) },
      go { case all_done(x) + done(_) if x > 0 => all_done(x-1) }
    )
    //    done.setLogLevel(2)
    val initialTime = LocalDateTime.now
    all_done(1)

    val d = make_ping_pong_stack(done, tp)
    d(pingPongCalls)

    val result = f(initialTime)
    tp.shutdownNow()
    result
  }

  val counterMultiplier = 10

  def benchmark10(count: Int, tp: Pool): Long = {

    val initialTime = LocalDateTime.now

    val a = m[Boolean]
    val collect = m[Int]
    val f = b[Unit, Int]
    val get = b[Unit, Int]

    site(tp)(
      go { case f(_, reply) => a(reply(123)) },
      go { case a(x) + collect(n) => collect(n + (if (x) 0 else 1)) },
      go { case collect(n) + get(_, reply) => reply(n) }
    )
    collect(0)

    val numberOfFailures = (1 to count*counterMultiplier).map { _ =>
      if (f.timeout(1.seconds)().isEmpty) 1 else 0
    }.sum

    // In this benchmark, we used to have about 4% numberOfFailures and about 2 numberOfFalseReplies in 100,000
    val numberOfFalseReplies = get()

    if (numberOfFailures != 0 || numberOfFalseReplies != 0)
      println(s"failures=$numberOfFailures, false replies=$numberOfFalseReplies (both should be 0)")

    elapsed(initialTime)
  }

  def make_counter_1_Jiansen(done: AsyName[Unit], counters: Int, init: Int): SynName[Unit,Unit] = {
    object b9c1 extends Join {
      object c extends AsyName[Int]
      object d extends SynName[Unit, Unit]

      join {
        case c(0) => done(())
        case c(n) and d(_) if n > 0 => c(n-1); d.reply(())
      }
    }
    import b9c1._
    (1 to counters).foreach(_ => c(init))
    // We return just one molecule.
    d
  }

  // emit a blocking molecule many times
  def benchmark9_1_Jiansen(count: Int, tp: Pool): Long = {

    object b9c1b extends Join {
      object done extends AsyName[Unit]
      object all_done extends AsyName[Int]
      object f extends SynName[LocalDateTime, Long]

      join {
        case all_done(0) and f(tInit) => f.reply(elapsed(tInit))
        case all_done(x) + done(_) if x > 0 => all_done(x-1)
      }
    }

    import b9c1b._

    val initialTime = LocalDateTime.now
    all_done(numberOfCounters)

    val d = make_counter_1_Jiansen(done, numberOfCounters, count)
    (1 to (count*numberOfCounters)).foreach{ _ => d(()) }

    val result = f(initialTime)
    result
  }


}