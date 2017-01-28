package io.chymyst.test

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import io.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers}

class GameOfLifeSpec extends FlatSpec with Matchers {

  behavior of "Game of Life"

  def getNewState(state0: Int, state1: Int, state2: Int, state3: Int, state4: Int, state5: Int, state6: Int, state7: Int, state8: Int): Int = {
    val sum1 = state0 + state1 + state2 + state3 + state4 + state5 + state6 + state7 + state8
    val newState = sum1 match {
      case 2 => state0
      case 3 => 1
      case _ => 0
    }
    newState
  }

  it should "run correctly using single-reaction implementation" in {
    case class Cell(x: Int, y: Int, t: Int, state: Int, label: (Int, Int))

    val c = m[Cell] // one cell
    val fc = m[(Int, Int, Int)] // cell state at final time
    val f = m[(Int, Array[Array[Int]])] // accumulator for cell states at final time
    val g = b[Unit, Array[Array[Int]]] // blocking molecule to fetch the final accumulator value

    val tp = new FixedPool(32)

    // Toroidal board of size n * n
    val boardSize = 1
    val emptyBoard: Array[Array[Int]] = Array.fill(boardSize)(Array.fill(boardSize)(0))

    val maxTimeStep = 1

    site(tp)(
      // One reaction implements the entire Game of Life computation.
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
        if x0 == (x1 + 1) % boardSize && x0 == (x2 - 1) % boardSize && x0 == x3 && x0 == (x4 + 1) % boardSize && x0 == (x5 - 1) % boardSize && x0 == x6 && x0 == (x7 + 1) % boardSize && x0 == (x8 - 1) % boardSize &&
          y0 == y1 && y0 == y2 && y0 == (y3 + 1) % boardSize && y0 == (y4 + 1) % boardSize && y0 == (y5 + 1) % boardSize && y0 == (y6 - 1) % boardSize && y0 == (y7 - 1) % boardSize && y0 == (y8 - 1) % boardSize &&
          t0 == t1 && t0 == t2 && t0 == t3 && t0 == t4 && t0 == t5 && t0 == t6 && t0 == t7 && t0 == t8 =>
        val newState = getNewState(state0, state1, state2, state3, state4, state5, state6, state7, state8)
        (-1 to 1).foreach(i => (-1 to 1).foreach(j => c(Cell(x0, y0, t0 + 1, newState, (i, j)))))
        if (t0 == maxTimeStep) fc((x0, y0, newState))
      },
      // These reactions are needed to fetch the state of the board at the final time.
      go { case fc((x, y, state)) + f((count, board)) => board(x)(y) = state; f((count - 1, board)) },
      go { case g(_, r) + f((0, board)) => r(board) + f((0, board)) },
      go { case _ => f((boardSize*boardSize, emptyBoard))}
    )
c.setLogLevel(1)
    val initBoard = Array(
      Array(1, 1, 0, 0, 0),
      Array(0, 1, 1, 0, 0),
      Array(1, 0, 0, 0, 0),
      Array(0, 0, 0, 0, 0),
      Array(0, 0, 0, 0, 0)
    )

    val initTime = LocalDateTime.now

    (0 until boardSize).foreach(x =>
      (0 until boardSize).foreach(y =>
        (-1 to 1).foreach(i =>
          (-1 to 1).foreach(j =>
            c(Cell(x, y, t = 0, state = initBoard(x)(y), label = (i, j)))
          )
        )
      )
    )

    val finalBoard = g()
    val elapsed = initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)
    println(s"Test with board size $boardSize took $elapsed ms. Final board at t=$maxTimeStep")
    println("|" + finalBoard.map(_.map(x => if (x == 0) " " else "*").mkString("|")).mkString("|\n|") + "|")
    tp.shutdownNow()
  }

  it should "run correctly using single-timeslice implementation" in {

  }

  it should "run correctly using 3D reaction implementation" in {

  }
}
