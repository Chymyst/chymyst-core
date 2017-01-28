package io.chymyst.test

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

    val cell = m[Cell]

    val tp = new FixedPool(4)

    val boardSize = 5
    // Toroidal board of size n * n
    val maxTimeSteps = 5

    site(tp)(
      go { case
        cell(Cell(x0, y0, t0, state0, (0, 0))) +
          cell(Cell(x1, y1, t1, state1, (1, 0))) +
          cell(Cell(x2, y2, t2, state2, (-1, 0))) +
          cell(Cell(x3, y3, t3, state3, (0, 1))) +
          cell(Cell(x4, y4, t4, state4, (1, 1))) +
          cell(Cell(x5, y5, t5, state5, (-1, 1))) +
          cell(Cell(x6, y6, t6, state6, (0, -1))) +
          cell(Cell(x7, y7, t7, state7, (1, 1))) +
          cell(Cell(x8, y8, t8, state8, (-1, -1)))
        if x0 == (x1 + 1) % boardSize && x0 == (x2 - 1) % boardSize && x0 == x3 && x0 == (x4 + 1) % boardSize && x0 == (x5 - 1) % boardSize && x0 == x6 && x0 == (x7 + 1) % boardSize && x0 == (x8 - 1) % boardSize &&
          y0 == x1 && y0 == y2 && y0 == (y3 + 1) % boardSize && y0 == (y4 + 1) % boardSize && y0 == (y5 + 1) % boardSize && y0 == (y6 - 1) % boardSize && y0 == (y7 - 1) % boardSize && y0 == (y8 - 1) % boardSize &&
          t0 == t1 && t0 == t2 && t0 == t3 && t0 == t4 && t0 == t5 && t0 == t6 && t0 == t7 && t0 == t8 =>
        val newState = getNewState(state0, state1, state2, state3, state4, state5, state6, state7, state8)
        (-1 to 1).foreach(i => (-1 to 1).foreach(j => cell(Cell(x0, y0, t0 + 1, newState, (i, j)))))
      }
    )

    val initBoard = Array(
      Array(0, 0, 0, 0, 0),
      Array(0, 1, 1, 0, 0),
      Array(0, 0, 1, 1, 0),
      Array(0, 1, 0, 0, 0),
      Array(0, 0, 0, 0, 0)
    )

    (0 until boardSize).foreach(x =>
      (0 until boardSize).foreach(y =>
        (-1 to 1).foreach(i =>
          (-1 to 1).foreach(j =>
            cell(Cell(x, y, 0, initBoard(x)(y), (i, j)))
          )
        )
      )
    )
    tp.shutdownNow()
  }

  it should "run correctly using single-timeslice implementation" in {

  }

  it should "run correctly using 3D reaction implementation" in {

  }
}
