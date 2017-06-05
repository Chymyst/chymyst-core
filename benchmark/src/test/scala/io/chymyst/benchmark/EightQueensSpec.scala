package io.chymyst.benchmark

import io.chymyst.benchmark.Common._
import io.chymyst.jc._
import io.chymyst.test.LogSpec

import scala.concurrent.duration.DurationInt

class EightQueensSpec extends LogSpec {

  def safe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
    x1 != x2 && y1 != y2 && (x1 - y1 != x2 - y2) && (x1 + y1 != x2 + y2)

  def display(x: Int, y: Int, position: Seq[(Int, Int)]): String =
    if (position.contains((x, y))) "Q" else "."

  def printPosition(b: Int, position: Seq[(Int, Int)]): String = {
    val boardString = (0 until b).map { y =>
      (0 until b).map { x => display(x, y, position) }.mkString("")
    }.mkString("|\n|")

    s"/${"-" * b}\\\n|$boardString|\n\\${"-" * b}/\n"
  }

  def run3Queens(supply: Int, boardSize: Int, iterations: Int): Unit = {
    val tp = FixedPool(2)
    val tp2 = FixedPool(2)
    val pos = m[(Int, Int)]
    val done = m[Seq[(Int, Int)]]
    val all_done = m[Unit]
    val counter = m[Int]
    val finished = b[Unit, Unit]

    site(tp)(
      go { case done(s) + counter(c) =>
        if (c < 5) println(printPosition(boardSize, s))
        if (c >= iterations) all_done() else counter(c + 1)
      },
      go { case all_done(_) + finished(_, r) => r() }
    )

    site(tp2)(go {
      case pos((x1, y1)) +
        pos((x2, y2)) +
        pos((x3, y3))
        if
        safe(x1, y1, x2, y2) &&
          safe(x1, y1, x3, y3) &&
          safe(x2, y2, x3, y3)
      =>
        val found = Seq(
          (x1, y1),
          (x2, y2),
          (x3, y3)
        )
        done(found)
        found.foreach(pos) // emit all molecules back
    }
    )

    (1 to supply).foreach(_ =>
      (0 until boardSize).foreach(i => (0 until boardSize).foreach(j => pos((i, j))))
    )
    counter(1)
    finished()
    tp.shutdownNow()
    tp2.shutdownNow()
  }

  def run5Queens(supply: Int, boardSize: Int, iterations: Int): Unit = {
    val tp = FixedPool(2)
    val tp2 = FixedPool(2)
    val pos = m[(Int, Int)]
    val done = m[Seq[(Int, Int)]]
    val all_done = m[Unit]
    val counter = m[Int]
    val finished = b[Unit, Unit]

    site(tp)(go { case done(s) + counter(c) =>
      if (c < 5) println(printPosition(boardSize, s))
      if (c >= iterations) all_done() else counter(c + 1)
    },
      go { case all_done(_) + finished(_, r) => r() }
    )

    site(tp2)(go {
      case pos((x1, y1)) +
        pos((x2, y2)) +
        pos((x3, y3)) +
        pos((x4, y4)) +
        pos((x5, y5))
        if
        safe(x1, y1, x2, y2) &&
          safe(x1, y1, x3, y3) &&
          safe(x1, y1, x4, y4) &&
          safe(x1, y1, x5, y5) &&
          safe(x2, y2, x3, y3) &&
          safe(x2, y2, x4, y4) &&
          safe(x2, y2, x5, y5) &&
          safe(x3, y3, x4, y4) &&
          safe(x3, y3, x5, y5) &&
          safe(x4, y4, x5, y5)
      =>
        val found = Seq(
          (x1, y1),
          (x2, y2),
          (x3, y3),
          (x4, y4),
          (x5, y5)
        )
        done(found)
        found.foreach(pos) // emit all molecules back
    })
    (1 to supply).foreach(_ =>
      (0 until boardSize).foreach(i => (0 until boardSize).foreach(j => pos((i, j))))
    )
    counter(1)
    finished()
    tp.shutdownNow()
    tp2.shutdownNow()
  }

  def run8Queens(supply: Int, boardSize: Int): Seq[(Int, Int)] = {
    val tp = FixedPool(1)
    val tp2 = FixedPool(2)
    val pos = m[(Int, Int)]
    val done = m[Seq[(Int, Int)]]
    val finished = b[Unit, Seq[(Int, Int)]]

    site(tp2)(
      go { case done(s) + finished(_, r) => r(s) }
    )

    site(tp)(go {
      case pos((x1, y1)) +
        pos((x2, y2)) +
        pos((x3, y3)) +
        pos((x4, y4)) +
        pos((x5, y5)) +
        pos((x6, y6)) +
        pos((x7, y7)) +
        pos((x8, y8))
        if
        safe(x1, y1, x2, y2) &&
          safe(x1, y1, x3, y3) &&
          safe(x1, y1, x4, y4) &&
          safe(x1, y1, x5, y5) &&
          safe(x2, y2, x3, y3) &&
          safe(x2, y2, x4, y4) &&
          safe(x2, y2, x5, y5) &&
          safe(x3, y3, x4, y4) &&
          safe(x3, y3, x5, y5) &&
          safe(x4, y4, x5, y5) &&

          safe(x1, y1, x6, y6) &&
          safe(x1, y1, x7, y7) &&
          safe(x1, y1, x8, y8) &&
          safe(x2, y2, x6, y6) &&
          safe(x2, y2, x7, y7) &&
          safe(x2, y2, x8, y8) &&
          safe(x3, y3, x6, y6) &&
          safe(x3, y3, x7, y7) &&
          safe(x3, y3, x8, y8) &&
          safe(x4, y4, x6, y6) &&
          safe(x4, y4, x7, y7) &&
          safe(x4, y4, x8, y8) &&

          safe(x5, y5, x6, y6) &&
          safe(x5, y5, x7, y7) &&
          safe(x5, y5, x8, y8) &&
          safe(x6, y6, x7, y7) &&
          safe(x6, y6, x8, y8) &&
          safe(x7, y7, x8, y8)
      =>
        val result = Seq(
          (x1, y1),
          (x2, y2),
          (x3, y3),
          (x4, y4),
          (x5, y5),
          (x6, y6),
          (x7, y7),
          (x8, y8)
        )
        //        println(printPosition(boardSize, result))
        done(result)
    })

    (1 to supply).foreach(_ =>
      (0 until boardSize).foreach(i => (0 until boardSize).foreach(j => pos((i, j))))
    )
    val found = finished()
    Seq(tp, tp2).foreach(_.shutdownNow())
    found
  }

  /** This algorithm does not perform backtracking and may stall if a configuration of queens is selected that
    * precludes any other queens. Therefore, we give a larger board size and time out, fetching the questionable configuration.
    */
  def runNQueens(nQueens: Int, supply: Int, boardSize: Int, iterations: Int, timeout: Int, tp: Pool, tp2: Pool): Unit = {
    val acc = m[Seq[(Int, Int)]]
    val done = m[Seq[(Int, Int)]]
    val pos = m[(Int, Int)]
    val fetch = b[Unit, Seq[(Int, Int)]]
    val finished = b[Unit, Seq[(Int, Int)]]

    site(tp2)(
      go { case done(s) + finished(_, r) => r(s) }
    )

    site(tp)(
      go { case acc(s) + fetch(_, r) ⇒ r(s) } onThreads tp2,
      go { case pos((x, y)) + acc(s) // ignore warning "non-variable type argument"
        if s.size < nQueens && s.forall { case (xx, yy) => safe(x, y, xx, yy) } =>
        val newS = s :+ ((x, y))
        if (newS.size == nQueens) {
          done(newS)
          newS.foreach(pos) // emit all molecules back only when we are done
        }
        else acc(newS)
      })
    (1 to supply).foreach(_ =>
      (0 until boardSize).foreach(i => (0 until boardSize).foreach(j => pos((i, j))))
    )

    val initTime = System.currentTimeMillis()
    // compute results
    val res = (1 to iterations).map { _ ⇒
      acc(Seq())
      finished.timeout()(timeout.milliseconds).orElse(fetch.timeout()(timeout.milliseconds)).getOrElse(Nil)
    }
    val badValuesCount = res.count(_.length < nQueens)
    val goodCount = res.length - badValuesCount
    val elapsed = System.currentTimeMillis() - initTime - badValuesCount * timeout
    println(s"using n-queens scheme, obtained $goodCount solutions for $nQueens-queens problem on $boardSize*$boardSize board in ${elapsed / goodCount.toDouble} ms per solution; backtracking was ignored $badValuesCount times")
    println(s"sample solutions:\n${res.filter(_.length == nQueens).take(3).map(s ⇒ printPosition(boardSize, s)).mkString("\n")}")
  }

  behavior of "eight queens problem - n-queens scheme with no backtracking"

  it should "obtain solutions for 3 queens on 4x4 board using n-queens scheme" in {
    // 3 queens exist when board size is at least a 4x4, and require no backtracking
    withPool(FixedPool(2))(tp2 => withPool(FixedPool(2)) { tp ⇒ runNQueens(nQueens = 3, supply = 10, boardSize = 4, iterations = 20000, timeout = 200, tp, tp2) })
  }

  it should "obtain solutions for 8 queens on 12x12 board using n-queens scheme" in {
    withPool(FixedPool(2))(tp2 => withPool(FixedPool(2)) { tp ⇒ runNQueens(nQueens = 8, supply = 10, boardSize = 10, iterations = 1000, timeout = 200, tp, tp2) })
  }

  it should "obtain solutions for 8 queens on 8x8 board using n-queens scheme" in {
    withPool(FixedPool(2))(tp2 => withPool(FixedPool(2)) { tp ⇒ runNQueens(nQueens = 8, supply = 10, boardSize = 8, iterations = 100, timeout = 200, tp, tp2) })
  }

  behavior of "eight queens problem - hard-coded number of queens, one RS"

  it should "obtain 1000 solutions for 3 queens on 4x4 board using 1 RS" in {
    val iterations = 1000
    val boardSize = 4
    val initTime = System.currentTimeMillis()
    // 3 queens exist when board size is at least a 4x4
    run3Queens(supply = 10, boardSize, iterations)
    println(s"using rigid scheme, obtained $iterations solutions for 3-queens problem on $boardSize*$boardSize board in ${elapsed(initTime)} ms")
  }

  it should "obtain 1000 solutions for 5 queens on 5x5 board using 1 RS" in {
    val iterations = 1000
    val boardSize = 5
    val initTime = System.currentTimeMillis()
    // 5 queens exist when board size is at least a 5x5
    run5Queens(supply = 10, boardSize, iterations)
    println(s"using rigid scheme, obtained $iterations solutions for 5-queens problem on $boardSize*$boardSize board in ${elapsed(initTime)} ms")
  }

  it should "obtain 1 solution for 8 queens on 12x12 board using 1 RS (very slow)" in {
    val iterations = 1
    val boardSize = 12
    val initTime = System.currentTimeMillis()
    val result = run8Queens(supply = 1, boardSize)
    println(printPosition(boardSize, result))
    println(s"using rigid scheme, obtained $iterations solution(s) for 8-queens problem on $boardSize*$boardSize board in ${elapsed(initTime)} ms")
  }

}
