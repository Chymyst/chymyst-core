package io.chymyst.benchmark

import java.time.LocalDateTime

import io.chymyst.benchmark.Common._
import io.chymyst.jc._

import code.jiansen.scalajoin._ // Use precompiled classes from Jiansen's Join.scala, which are in that package.

object Benchmarks1 {

  def benchmark1(count: Int, tp: Pool): Long = {

    val c = m[Int]
    val g = b[Unit,Int]
    val i = m[Unit]
    val d = m[Unit]
    val f = b[LocalDateTime,Long]

    withPool(new FixedPool(8)) { tp1 =>
      site(tp)( // Right now we don't use tp1. If we use `site(tp, tp1)`, the run time is increased by factor 2.
        go { case c(0) + f(tInit, r) =>
          r(elapsed(tInit))
        },
        go { case g(_, reply) + c(n) => c(n); reply(n) },
        go { case c(n) + i(_) => c(n + 1) },
        go { case c(n) + d(_) if n > 0 => c(n - 1) }
      )

      c(count)
      val initialTime = LocalDateTime.now
      (1 to count).foreach { _ => d() }
      f(initialTime)
    }.get
  }

  def make_counterJoinScala(init: Int): (AsyName[Unit],AsyName[Unit],SynName[LocalDateTime, Long],SynName[Unit,Int]) = {
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

  def make_counterChymyst(init: Int, tp: Pool) = {
    val c = m[Int]
    val g = b[Unit,Int]
    val i = m[Unit]
    val d = m[Unit]
    val f = b[LocalDateTime,Long]

    site(tp)(
      go { case c(0) + f(tInit, r) => r(elapsed(tInit)) },
      go { case g(_, reply) + c(n) => c(n); reply(n) },
      go { case c(n) + i(_) => c(n + 1) },
      go { case c(n) + d(_) if n > 0 => c(n - 1) }
    )

    c(init)
    (d,i,f,g)
  }

  def benchmark2(count: Int, tp: Pool): Long = {

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

    val initialTime = LocalDateTime.now
    (1 to count).foreach{ _ => j2.d(()) }
    j2.f(initialTime)
  }

  def benchmark2a(count: Int, tp: Pool): Long = {

    val initialTime = LocalDateTime.now

    val (d,_,f,_) = make_counterJoinScala(count)
    (1 to count).foreach{ _ => d(()) }
    f(initialTime)
  }

  def benchmark3(count: Int, tp: Pool): Long = {

    val initialTime = LocalDateTime.now

    val (d,_,f,_) = make_counterChymyst(count, tp)
    (1 to count).foreach{ _ => d() }

    f(initialTime)
  }

}