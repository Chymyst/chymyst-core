package io.chymyst.test

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import io.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers}

class GameOfLifeSpec extends FlatSpec with Matchers {

  behavior of "Game of Life"

  def getNewState(state0: Int, state1: Int, state2: Int, state3: Int, state4: Int, state5: Int, state6: Int, state7: Int, state8: Int): Int = {
    val sum1 = state1 + state2 + state3 + state4 + state5 + state6 + state7 + state8
    val newState = sum1 match {
      case 2 => state0
      case 3 => 1
      case _ => 0
    }
    newState
  }

  case class BoardSize(x: Int, y: Int)

  it should "run correctly using 1-reaction 1-molecule implementation (the absolute worst)" in {
    case class Cell(x: Int, y: Int, t: Int, state: Int, label: (Int, Int))

    // one cell
    val c = m[Cell]

    // cell state at final time
    val fc = m[(Int, Int, Int)]

    // accumulator for cell states at final time
    val f = m[(Int, Array[Array[Int]])]

    // blocking molecule to fetch the final accumulator value
    val g = b[Unit, Array[Array[Int]]]

    val tp = new FixedPool(4)

    // Toroidal board of size m * n
    val boardSize = BoardSize(2, 2)
    val emptyBoard: Array[Array[Int]] = Array.fill(boardSize.y)(Array.fill(boardSize.x)(0))

    val maxTimeStep = 1

    site(tp)(
      // One reaction implements the entire Game of Life computation. Shifted positions are represented as the "label" field.
      go { case
        c(Cell(x0, y0, t0, state0, (0, 0))) +
          c(Cell(x1, y1, t1, state1, (1, 0))) +
          c(Cell(x2, y2, t2, state2, (-1, 0))) +
          c(Cell(x3, y3, t3, state3, (0, 1))) +
          c(Cell(x4, y4, t4, state4, (1, 1))) +
          c(Cell(x5, y5, t5, state5, (-1, 1))) +
          c(Cell(x6, y6, t6, state6, (0, -1))) +
          c(Cell(x7, y7, t7, state7, (1, -1))) +
          c(Cell(x8, y8, t8, state8, (-1, -1)))
        if x0 == x1 && x0 == x2 && x0 == x3 && x0 == x4 && x0 == x5 && x0 == x6 && x0 == x7 && x0 == x8 &&
          y0 == y1 && y0 == y2 && y0 == y3 && y0 == y4 && y0 == y5 && y0 == y6 && y0 == y7 && y0 == y8 &&
          t0 == t1 && t0 == t2 && t0 == t3 && t0 == t4 && t0 == t5 && t0 == t6 && t0 == t7 && t0 == t8 =>
        val newState = getNewState(state0, state1, state2, state3, state4, state5, state6, state7, state8)
        (-1 to 1).foreach(i => (-1 to 1).foreach(j => c(Cell((x0 + i + boardSize.x) % boardSize.x, (y0 + j + boardSize.y) % boardSize.y, t0 + 1, newState, (i, j)))))
        if (t0 + 1 == maxTimeStep) fc((x0, y0, newState))
      },
      // These reactions are needed to fetch the state of the board at the final time.
      go { case fc((x, y, state)) + f((count, board)) => board(y)(x) = state; f((count - 1, board)) },
      go { case g(_, r) + f((0, board)) => r(board) + f((0, board)) },
      go { case _ => f((boardSize.x * boardSize.y, emptyBoard)) }
    )

    //    val initBoard = Array(
    //      Array(1, 1, 0, 0, 0),
    //      Array(0, 1, 1, 0, 0),
    //      Array(1, 0, 0, 0, 0),
    //      Array(0, 0, 0, 0, 0),
    //      Array(0, 0, 0, 0, 0)
    //    )
    val initBoard = Array(
      Array(1, 1, 0, 0, 0),
      Array(0, 0, 1, 0, 0),
      Array(1, 0, 0, 0, 0),
      Array(0, 0, 0, 0, 0),
      Array(0, 0, 0, 0, 0)
    )
    val initTime = LocalDateTime.now

    (0 until boardSize.y).foreach(y0 =>
      (0 until boardSize.x).foreach(x0 =>
        (-1 to 1).foreach(i =>
          (-1 to 1).foreach(j =>
            c(Cell((x0 + i + boardSize.x) % boardSize.x, (y0 + j + boardSize.y) % boardSize.y, t = 0, state = initBoard(y0)(x0), label = (i, j)))
          )
        )
      )
    )

    val finalBoard = g()
    val elapsed = initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)
    println(s"Test (1 reaction, 1 molecule) with $boardSize took $elapsed ms. Final board at t=$maxTimeStep:")
    println("|" + finalBoard.map(_.map(x => if (x == 0) " " else "*").mkString("|")).mkString("|\n|") + "|")
    finalBoard shouldEqual Array(Array(1, 1), Array(0, 0))
    tp.shutdownNow()
  }

  it should "run correctly using 1-reaction 9-molecule implementation" in {
    case class Cell(x: Int, y: Int, t: Int, state: Int)

    val c0 = m[Cell]
    val c1 = m[Cell]
    val c2 = m[Cell]
    val c3 = m[Cell]
    val c4 = m[Cell]
    val c5 = m[Cell]
    val c6 = m[Cell]
    val c7 = m[Cell]
    val c8 = m[Cell]

    // cell state at final time
    val fc = m[(Int, Int, Int)]

    // accumulator for cell states at final time
    val f = m[(Int, Array[Array[Int]])]

    // blocking molecule to fetch the final accumulator value
    val g = b[Unit, Array[Array[Int]]]

    val tp = new FixedPool(4)

    // Toroidal board of size m * n
    val boardSize = BoardSize(2, 2)
    val emptyBoard: Array[Array[Int]] = Array.fill(boardSize.y)(Array.fill(boardSize.x)(0))

    val maxTimeStep = 1

    site(tp)(
      // One reaction implements the entire Game of Life computation. Different molecules are used for shifted positions.
      go { case
        c0(Cell(x0, y0, t0, state0)) +
          c1(Cell(x1, y1, t1, state1)) +
          c2(Cell(x2, y2, t2, state2)) +
          c3(Cell(x3, y3, t3, state3)) +
          c4(Cell(x4, y4, t4, state4)) +
          c5(Cell(x5, y5, t5, state5)) +
          c6(Cell(x6, y6, t6, state6)) +
          c7(Cell(x7, y7, t7, state7)) +
          c8(Cell(x8, y8, t8, state8))
        if x0 == x1 && x0 == x2 && x0 == x3 && x0 == x4 && x0 == x5 && x0 == x6 && x0 == x7 && x0 == x8 &&
          y0 == y1 && y0 == y2 && y0 == y3 && y0 == y4 && y0 == y5 && y0 == y6 && y0 == y7 && y0 == y8 &&
          t0 == t1 && t0 == t2 && t0 == t3 && t0 == t4 && t0 == t5 && t0 == t6 && t0 == t7 && t0 == t8 =>
        val newState = getNewState(state0, state1, state2, state3, state4, state5, state6, state7, state8)

        c0(Cell((x0 + 0 + boardSize.x) % boardSize.x, (y0 + 0 + boardSize.y) % boardSize.y, t0 + 1, newState))
        c1(Cell((x0 + 1 + boardSize.x) % boardSize.x, (y0 + 0 + boardSize.y) % boardSize.y, t0 + 1, newState))
        c2(Cell((x0 - 1 + boardSize.x) % boardSize.x, (y0 + 0 + boardSize.y) % boardSize.y, t0 + 1, newState))
        c3(Cell((x0 + 0 + boardSize.x) % boardSize.x, (y0 + 1 + boardSize.y) % boardSize.y, t0 + 1, newState))
        c4(Cell((x0 + 1 + boardSize.x) % boardSize.x, (y0 + 1 + boardSize.y) % boardSize.y, t0 + 1, newState))
        c5(Cell((x0 - 1 + boardSize.x) % boardSize.x, (y0 + 1 + boardSize.y) % boardSize.y, t0 + 1, newState))
        c6(Cell((x0 + 0 + boardSize.x) % boardSize.x, (y0 - 1 + boardSize.y) % boardSize.y, t0 + 1, newState))
        c7(Cell((x0 + 1 + boardSize.x) % boardSize.x, (y0 - 1 + boardSize.y) % boardSize.y, t0 + 1, newState))
        c8(Cell((x0 - 1 + boardSize.x) % boardSize.x, (y0 - 1 + boardSize.y) % boardSize.y, t0 + 1, newState))

        if (t0 + 1 == maxTimeStep) fc((x0, y0, newState))
      },
      // These reactions are needed to fetch the state of the board at the final time.
      go { case fc((x, y, state)) + f((count, board)) => board(y)(x) = state; f((count - 1, board)) },
      go { case g(_, r) + f((0, board)) => r(board) + f((0, board)) },
      go { case _ => f((boardSize.x * boardSize.y, emptyBoard)) }
    )

    //    val initBoard = Array(
    //      Array(1, 1, 0, 0, 0),
    //      Array(0, 1, 1, 0, 0),
    //      Array(1, 0, 0, 0, 0),
    //      Array(0, 0, 0, 0, 0),
    //      Array(0, 0, 0, 0, 0)
    //    )
    val initBoard = Array(
      Array(1, 1, 0, 0, 0),
      Array(0, 0, 1, 0, 0),
      Array(1, 0, 0, 0, 0),
      Array(0, 0, 0, 0, 0),
      Array(0, 0, 0, 0, 0)
    )
    val initTime = LocalDateTime.now

    (0 until boardSize.y).foreach { y0 =>
      (0 until boardSize.x).foreach { x0 =>
        val newState = initBoard(y0)(x0)
        c0(Cell((x0 + 0 + boardSize.x) % boardSize.x, (y0 + 0 + boardSize.y) % boardSize.y, 0, newState))
        c1(Cell((x0 + 1 + boardSize.x) % boardSize.x, (y0 + 0 + boardSize.y) % boardSize.y, 0, newState))
        c2(Cell((x0 - 1 + boardSize.x) % boardSize.x, (y0 + 0 + boardSize.y) % boardSize.y, 0, newState))
        c3(Cell((x0 + 0 + boardSize.x) % boardSize.x, (y0 + 1 + boardSize.y) % boardSize.y, 0, newState))
        c4(Cell((x0 + 1 + boardSize.x) % boardSize.x, (y0 + 1 + boardSize.y) % boardSize.y, 0, newState))
        c5(Cell((x0 - 1 + boardSize.x) % boardSize.x, (y0 + 1 + boardSize.y) % boardSize.y, 0, newState))
        c6(Cell((x0 + 0 + boardSize.x) % boardSize.x, (y0 - 1 + boardSize.y) % boardSize.y, 0, newState))
        c7(Cell((x0 + 1 + boardSize.x) % boardSize.x, (y0 - 1 + boardSize.y) % boardSize.y, 0, newState))
        c8(Cell((x0 - 1 + boardSize.x) % boardSize.x, (y0 - 1 + boardSize.y) % boardSize.y, 0, newState))
      }
    }

    val finalBoard = g()
    val elapsed = initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)
    println(s"Test (1 reaction, 9 molecules) with $boardSize took $elapsed ms. Final board at t=$maxTimeStep:")
    println("|" + finalBoard.map(_.map(x => if (x == 0) " " else "*").mkString("|")).mkString("|\n|") + "|")
    finalBoard shouldEqual Array(Array(1, 1), Array(0, 0))
    tp.shutdownNow()
  }

  it should "run correctly using single-timeslice implementation" in {

  }

  it should "run correctly using 3D reaction implementation" in {

  }
}
