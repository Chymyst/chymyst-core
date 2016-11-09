package code.winitzki.benchmark

import java.time.LocalDateTime
import code.winitzki.benchmark.Common._
import code.winitzki.jc._
import code.winitzki.jc.JoinRun._

object Benchmarks1 {

  def benchmark1(count: Int, threads: Int = 2): Long = {

    val c = ja[Int]("c")
    val g = js[Unit,Int]("g")
    val i = ja[Unit]("i")
    val d = ja[Unit]("d")
    val f = js[LocalDateTime,Long]("f")

    val tp = new JProcessPool(threads)

    join(
      run { case c(0) + f(tInit, r) =>
        val t = LocalDateTime.now
        r(elapsed(tInit))
      } onThreads tp,
      tp{ case g(_,reply) + c(n) => c(n); reply(n) },
      tp{ case c(n) + i(_) => c(n+1)  },
      tp{ case c(n) + d(_) if n > 0 => c(n-1) }
    )

    val initialTime = LocalDateTime.now
    c(count)
    (1 to count).foreach{ _ => d() }

    val result = f(initialTime)
    tp.shutdownNow()
    result
  }

  def make_counter2a(init: Int, threads: Int): (AsyName[Unit],AsyName[Unit],SynName[LocalDateTime, Long],SynName[Unit,Int]) = {
    object j2 extends Join {
      object c extends AsyName[Int]
      object g extends SynName[Unit, Int]
      object i extends AsyName[Unit]
      object d extends AsyName[Unit]
      object f extends SynName[LocalDateTime, Long]

      join {
        case c(0) and f(tInit) =>
          f.reply(elapsed(tInit))
        case c(n) and d(_) if n > 0 => c(n-1)
        case c(n) and i(_) => c(n+1)
        case c(n) and g(_) => c(n); g.reply(n)
      }

    }
    j2.c(init)
    (j2.d,j2.i,j2.f,j2.g)
  }

  def make_counter(init: Int, threads: Int, tp: JProcessPool) = {
    val c = ja[Int]("c")
    val g = js[Unit,Int]("g")
    val i = ja[Unit]("i")
    val d = ja[Unit]("d")
    val f = js[LocalDateTime,Long]("f")

    join(
      tp { case c(0) + f(tInit, r) =>
        val t = LocalDateTime.now
        r(elapsed(tInit))
      },
      tp{ case g(_,reply) + c(n) => c(n); reply(n) },
      tp{ case c(n) + i(_) => c(n+1) },
      tp{ case c(n) + d(_) if n > 0 => c(n-1) }
    )
    c(init)
    (d,i,f,g)
  }

  def benchmark2(count: Int, threads: Int = 2): Long = {

    val initialTime = LocalDateTime.now
    object j2 extends Join {
      object c extends AsyName[Int]
      object g extends SynName[Unit, Int]
      object i extends AsyName[Unit]
      object d extends AsyName[Unit]
      object f extends SynName[LocalDateTime, Long]

      join {
        case c(0) and f(tInit) =>
          f.reply(elapsed(tInit))
        case c(n) and d(_) if n > 0 => c(n-1)
        case c(n) and i(_) => c(n+1)
        case c(n) and g(_) => c(n); g.reply(n)
      }

    }
    j2.c(count)

    (1 to count).foreach{ _ => j2.d() }
    j2.f(initialTime)
  }

  def benchmark2a(count: Int, threads: Int = 2): Long = {

    val initialTime = LocalDateTime.now

    val (d,_,f,_) = make_counter2a(count, threads)
    (1 to count).foreach{ _ => d() }
    f(initialTime)
  }

  def benchmark3(count: Int, threads: Int = 2): Long = {

    val initialTime = LocalDateTime.now

    val tp = new JProcessPool(threads)

    val (d,_,f,_) = make_counter(count, threads, tp)
    (1 to count).foreach{ _ => d() }

    val result = f(initialTime)
    tp.shutdownNow()
    result
  }

}