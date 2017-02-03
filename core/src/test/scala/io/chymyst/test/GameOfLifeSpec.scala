package io.chymyst.test

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import io.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers}

class GameOfLifeSpec extends FlatSpec with Matchers {

  behavior of "Game of Life"

  def getNewState(state0: Int, state1: Int, state2: Int, state3: Int, state4: Int, state5: Int, state6: Int, state7: Int, state8: Int): Int =
    (state1 + state2 + state3 + state4 + state5 + state6 + state7 + state8) match {
      case 2 => state0
      case 3 => 1
      case _ => 0
    }

  case class BoardSize(x: Int, y: Int) {
    def area: Int = x * y
  }

  // This implementation is intentionally as inefficient as possible.
  it should "1. run correctly using 1-reaction 1-molecule implementation (the absolute worst)" in {
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
      // One reaction with one molecule `c` implements the entire Game of Life computation.
      // Shifted positions are represented by the "Cell#label" field.
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

    val initBoard = Array(
      Array(1, 1, 1, 1, 1),
      Array(0, 0, 0, 0, 0),
      Array(0, 0, 0, 0, 0),
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
    println(s"Test (1 reaction, 1 molecule) with $boardSize and $maxTimeStep timesteps took $elapsed ms. Final board at t=$maxTimeStep:")
    println("|" + finalBoard.map(_.map(x => if (x == 0) " " else "*").mkString("|")).mkString("|\n|") + "|")
    finalBoard shouldEqual Array(Array(1, 1), Array(0, 0))
    tp.shutdownNow()
  }

  it should "2. run correctly using 1-reaction 9-molecule implementation" in {
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

    val initBoard = Array(
      Array(1, 1, 1, 1, 1),
      Array(0, 0, 0, 0, 0),
      Array(0, 0, 0, 0, 0),
      Array(0, 0, 0, 0, 0),
      Array(0, 0, 0, 0, 0)
    )
    val initTime = LocalDateTime.now

    (0 until boardSize.y).foreach { y0 =>
      (0 until boardSize.x).foreach { x0 =>
        val initState = initBoard(y0)(x0)
        c0(Cell((x0 + 0 + boardSize.x) % boardSize.x, (y0 + 0 + boardSize.y) % boardSize.y, 0, initState))
        c1(Cell((x0 + 1 + boardSize.x) % boardSize.x, (y0 + 0 + boardSize.y) % boardSize.y, 0, initState))
        c2(Cell((x0 - 1 + boardSize.x) % boardSize.x, (y0 + 0 + boardSize.y) % boardSize.y, 0, initState))
        c3(Cell((x0 + 0 + boardSize.x) % boardSize.x, (y0 + 1 + boardSize.y) % boardSize.y, 0, initState))
        c4(Cell((x0 + 1 + boardSize.x) % boardSize.x, (y0 + 1 + boardSize.y) % boardSize.y, 0, initState))
        c5(Cell((x0 - 1 + boardSize.x) % boardSize.x, (y0 + 1 + boardSize.y) % boardSize.y, 0, initState))
        c6(Cell((x0 + 0 + boardSize.x) % boardSize.x, (y0 - 1 + boardSize.y) % boardSize.y, 0, initState))
        c7(Cell((x0 + 1 + boardSize.x) % boardSize.x, (y0 - 1 + boardSize.y) % boardSize.y, 0, initState))
        c8(Cell((x0 - 1 + boardSize.x) % boardSize.x, (y0 - 1 + boardSize.y) % boardSize.y, 0, initState))
      }
    }

    val finalBoard = g()
    val elapsed = initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)
    println(s"Test (1 reaction, 9 molecules) with $boardSize and $maxTimeStep timesteps took $elapsed ms. Final board at t=$maxTimeStep:")
    println("|" + finalBoard.map(_.map(x => if (x == 0) " " else "*").mkString("|")).mkString("|\n|") + "|")
    finalBoard shouldEqual Array(Array(1, 1), Array(0, 0))
    tp.shutdownNow()
  }

  it should "3. run correctly using 9-molecule single-timeslice implementation" in {
    case class Cell(t: Int, state: Int)

    // cell state at final time
    val fc = m[(Int, Int, Int)]

    // accumulator for cell states at final time
    val f = m[(Int, Array[Array[Int]])]

    // blocking molecule to fetch the final accumulator value
    val g = b[Unit, Array[Array[Int]]]

    val tp = new FixedPool(16)

    // Toroidal board of size m * n
    val boardSize = BoardSize(10, 10)
    val maxTimeStep = 10

    val total = boardSize.x * boardSize.y
    val emptyBoard: Array[Array[Int]] = Array.fill(boardSize.x, boardSize.y)(0)

    def xm(x: Int): Int = (x + boardSize.x) % boardSize.x

    def ym(y: Int): Int = (y + boardSize.y) % boardSize.y

    // Emitters for cells at position (x, y)
    val emitterMatrix: Array[Array[Array[M[Cell]]]] = Array.tabulate(boardSize.x, boardSize.y, 9)((x, y, label) => new M[Cell](s"c$label[$x,$y]"))

    // Reaction for cell at position (x, y)
    val reactionMatrix: Array[Array[Reaction]] = Array.tabulate(boardSize.x, boardSize.y) { (x, y) =>
      // Molecule emitters for the inputs.
      val c0 = emitterMatrix(x)(y)(0)
      val c1 = emitterMatrix(x)(y)(1)
      val c2 = emitterMatrix(x)(y)(2)
      val c3 = emitterMatrix(x)(y)(3)
      val c4 = emitterMatrix(x)(y)(4)
      val c5 = emitterMatrix(x)(y)(5)
      val c6 = emitterMatrix(x)(y)(6)
      val c7 = emitterMatrix(x)(y)(7)
      val c8 = emitterMatrix(x)(y)(8)

      // Molecule emitters for the outputs.
      val d0 = emitterMatrix(xm(x + 0))(ym(y + 0))(0)
      val d1 = emitterMatrix(xm(x + 1))(ym(y + 0))(1)
      val d2 = emitterMatrix(xm(x - 1))(ym(y + 0))(2)
      val d3 = emitterMatrix(xm(x + 0))(ym(y + 1))(3)
      val d4 = emitterMatrix(xm(x + 1))(ym(y + 1))(4)
      val d5 = emitterMatrix(xm(x - 1))(ym(y + 1))(5)
      val d6 = emitterMatrix(xm(x + 0))(ym(y - 1))(6)
      val d7 = emitterMatrix(xm(x + 1))(ym(y - 1))(7)
      val d8 = emitterMatrix(xm(x - 1))(ym(y - 1))(8)

      go { case
        c0(Cell(t0, state0)) +
          c1(Cell(t1, state1)) +
          c2(Cell(t2, state2)) +
          c3(Cell(t3, state3)) +
          c4(Cell(t4, state4)) +
          c5(Cell(t5, state5)) +
          c6(Cell(t6, state6)) +
          c7(Cell(t7, state7)) +
          c8(Cell(t8, state8))
        if t0 == t1 && t0 == t2 && t0 == t3 && t0 == t4 && t0 == t5 && t0 == t6 && t0 == t7 && t0 == t8 =>
        val newState = getNewState(state0, state1, state2, state3, state4, state5, state6, state7, state8)
        d0(Cell(t0 + 1, newState)) +
          d1(Cell(t0 + 1, newState)) +
          d2(Cell(t0 + 1, newState)) +
          d3(Cell(t0 + 1, newState)) +
          d4(Cell(t0 + 1, newState)) +
          d5(Cell(t0 + 1, newState)) +
          d6(Cell(t0 + 1, newState)) +
          d7(Cell(t0 + 1, newState)) +
          d8(Cell(t0 + 1, newState))
        if (t0 + 1 == maxTimeStep) fc((x, y, newState))
      }
    }

    // These reactions are needed to fetch the state of the board at the final time.
    site(tp)(
      go { case fc((x, y, state)) + f((count, board)) => board(x)(y) = state; f((count - 1, board)) },
      go { case g(_, r) + f((0, board)) => r(board) + f((0, board)) },
      go { case _ => f((total, emptyBoard)) }
    )

    // All cell reactions must be in one reaction site since they share all the cell molecules from the entire `emitterMatrix`.
    site(tp)(reactionMatrix.flatten: _*)

    // The "glider" configuration.
    val initBoard = Array(
      Array(1, 1, 0, 0, 0),
      Array(0, 1, 1, 0, 0),
      Array(1, 0, 0, 0, 0),
      Array(0, 0, 0, 0, 0),
      Array(0, 0, 0, 0, 0)
    )
    val initTime = LocalDateTime.now

    (0 until boardSize.y).foreach { y0 =>
      (0 until boardSize.x).foreach { x0 =>
        val initState = initBoard.lift(x0).getOrElse(Array()).lift(y0).getOrElse(0)
        emitterMatrix(xm(x0 + 0))(ym(y0 + 0))(0)(Cell(0, initState))
        emitterMatrix(xm(x0 + 1))(ym(y0 + 0))(1)(Cell(0, initState))
        emitterMatrix(xm(x0 - 1))(ym(y0 + 0))(2)(Cell(0, initState))
        emitterMatrix(xm(x0 + 0))(ym(y0 + 1))(3)(Cell(0, initState))
        emitterMatrix(xm(x0 + 1))(ym(y0 + 1))(4)(Cell(0, initState))
        emitterMatrix(xm(x0 - 1))(ym(y0 + 1))(5)(Cell(0, initState))
        emitterMatrix(xm(x0 + 0))(ym(y0 - 1))(6)(Cell(0, initState))
        emitterMatrix(xm(x0 + 1))(ym(y0 - 1))(7)(Cell(0, initState))
        emitterMatrix(xm(x0 - 1))(ym(y0 - 1))(8)(Cell(0, initState))
      }
    }

    val finalBoard = g()
    val elapsed = initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)
    println(s"Test ($total reactions, 9 molecules) with $boardSize and $maxTimeStep timesteps took $elapsed ms. Final board at t=$maxTimeStep:")
    val finalPicture = "|" + finalBoard.map(_.map(x => if (x == 0) " " else "*").mkString("|")).mkString("|\n|") + "|"
    println(finalPicture)
    // The "glider" should move, wrapping around the toroidal board.
    finalPicture shouldEqual
     """|| | | | | | | | | | |
        || | | | | | | | | | |
        || | | | | | | | | | |
        || | | | | | | | | | |
        || | | | | | | | | | |
        || | | | | | | | | | |
        || | | | | | | | | | |
        || | | |*| | | | | | |
        || | | |*|*| | | | | |
        || | |*| |*| | | | | |""".stripMargin
    tp.shutdownNow()
  }

  // Three implementations with per-cell reactions. They use the same chemistry in `run3D` but define reaction sites differently, achieving more speed the more granular the reaction sites become.

  // Run Game of Life on a toroidal board of size m * n; return final board at time = finalTimeStep
  def run3D(boardSize: BoardSize, finalTimeStep: Int, initBoard: Array[Array[Int]], declareSites: (Pool, Array[Array[Array[Reaction]]]) => Any): Array[Array[Int]] = {
    // cell state at final time
    val fc = m[(Int, Int, Int)]

    // accumulator for cell states at final time
    val f = m[(Int, Array[Array[Int]])]

    // blocking molecule to fetch the final accumulator value
    val g = b[Unit, Array[Array[Int]]]

    val tp = new FixedPool(16) // maximum parallelism

    val emptyBoard: Array[Array[Int]] = Array.fill(boardSize.x, boardSize.y)(0)

    def xm(x: Int): Int = (x + boardSize.x) % boardSize.x

    def ym(y: Int): Int = (y + boardSize.y) % boardSize.y

    // Emitters for cells at 3D position (x, y, t)
    val emitterMatrix: Array[Array[Array[Array[M[Int]]]]] = Array.tabulate(boardSize.x, boardSize.y, finalTimeStep, 9)((x, y, t, label) => new M[Int](s"c$label[$x,$y,$t]"))

    // Reaction for cell at position (x, y)
    val reactionMatrix: Array[Array[Array[Reaction]]] = Array.tabulate(boardSize.x, boardSize.y, finalTimeStep) { (x, y, t) =>
      // Molecule emitters for the inputs.
      val c0 = emitterMatrix(x)(y)(t)(0)
      val c1 = emitterMatrix(x)(y)(t)(1)
      val c2 = emitterMatrix(x)(y)(t)(2)
      val c3 = emitterMatrix(x)(y)(t)(3)
      val c4 = emitterMatrix(x)(y)(t)(4)
      val c5 = emitterMatrix(x)(y)(t)(5)
      val c6 = emitterMatrix(x)(y)(t)(6)
      val c7 = emitterMatrix(x)(y)(t)(7)
      val c8 = emitterMatrix(x)(y)(t)(8)

      go { case
        c0(state0) +
          c1(state1) +
          c2(state2) +
          c3(state3) +
          c4(state4) +
          c5(state5) +
          c6(state6) +
          c7(state7) +
          c8(state8) =>
        val newState = getNewState(state0, state1, state2, state3, state4, state5, state6, state7, state8)

        if (t + 1 == finalTimeStep) fc((x, y, newState))
        else {
          // Molecule emitters for the outputs.
          emitterMatrix(xm(x + 0))(ym(y + 0))(t + 1)(0)(newState)
          emitterMatrix(xm(x + 1))(ym(y + 0))(t + 1)(1)(newState)
          emitterMatrix(xm(x - 1))(ym(y + 0))(t + 1)(2)(newState)
          emitterMatrix(xm(x + 0))(ym(y + 1))(t + 1)(3)(newState)
          emitterMatrix(xm(x + 1))(ym(y + 1))(t + 1)(4)(newState)
          emitterMatrix(xm(x - 1))(ym(y + 1))(t + 1)(5)(newState)
          emitterMatrix(xm(x + 0))(ym(y - 1))(t + 1)(6)(newState)
          emitterMatrix(xm(x + 1))(ym(y - 1))(t + 1)(7)(newState)
          emitterMatrix(xm(x - 1))(ym(y - 1))(t + 1)(8)(newState)
        }
      }
    }

    // These reactions are needed to fetch the state of the board at the final time.
    site(tp)(
      go { case fc((x, y, state)) + f((count, board)) => board(x)(y) = state; f((count - 1, board)) },
      go { case g(_, r) + f((0, board)) => r(board) + f((0, board)) },
      go { case _ => f((boardSize.area, emptyBoard)) }
    )

    // All cell reactions must be in one reaction site since they share all the cell molecules from the entire `emitterMatrix`.
    declareSites(tp, reactionMatrix)

    (0 until boardSize.y).foreach { y0 =>
      (0 until boardSize.x).foreach { x0 =>
        val initState = initBoard.lift(x0).getOrElse(Array()).lift(y0).getOrElse(0)
        emitterMatrix(xm(x0 + 0))(ym(y0 + 0))(0)(0)(initState)
        emitterMatrix(xm(x0 + 1))(ym(y0 + 0))(0)(1)(initState)
        emitterMatrix(xm(x0 - 1))(ym(y0 + 0))(0)(2)(initState)
        emitterMatrix(xm(x0 + 0))(ym(y0 + 1))(0)(3)(initState)
        emitterMatrix(xm(x0 + 1))(ym(y0 + 1))(0)(4)(initState)
        emitterMatrix(xm(x0 - 1))(ym(y0 + 1))(0)(5)(initState)
        emitterMatrix(xm(x0 + 0))(ym(y0 - 1))(0)(6)(initState)
        emitterMatrix(xm(x0 + 1))(ym(y0 - 1))(0)(7)(initState)
        emitterMatrix(xm(x0 - 1))(ym(y0 - 1))(0)(8)(initState)
      }
    }

    val finalBoard = g()
    tp.shutdownNow()

    finalBoard
  }

  def checkResult(finalBoard: Array[Array[Int]]): Unit = {
    val finalPicture = "|" + finalBoard.map(_.map(x => if (x == 0) " " else "*").mkString("|")).mkString("|\n|") + "|"
    println(finalPicture)
    // The "glider" should move, wrapping around the toroidal board.
    finalPicture shouldEqual
     """|| | | | | | | | | | |
        || | | | | | | | | | |
        || | | | | | | | | | |
        || | | | | | | | | | |
        || | | | | | | | | | |
        || | | | | | | | | | |
        || | | | | | | | | | |
        || | | |*| | | | | | |
        || | | |*|*| | | | | |
        || | |*| |*| | | | | |""".stripMargin
    ()
  }

  // The "glider" configuration.
  val initBoard = Array(
    Array(1, 1, 0),
    Array(0, 1, 1),
    Array(1, 0, 0)
  )

  val maxTimeStep = 10

  val boardSize = BoardSize(10, 10)

  it should "4. run correctly using 3D reaction implementation with single reaction site" in {
    val initTime = LocalDateTime.now
    // Define one reaction site for all reactions.
    val finalBoard = run3D(boardSize, maxTimeStep, initBoard, (tp, reactionMatrix) => site(tp)(reactionMatrix.flatten.flatten: _*))
    val elapsed = initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)
    println(s"Test (${boardSize.area * maxTimeStep} reactions, 9 molecules) with $boardSize and $maxTimeStep timesteps took $elapsed ms. Final board at t=$maxTimeStep:")
    checkResult(finalBoard)
  }

  it should "5. run correctly using 3D reaction implementation with per-timeslice reaction sites" in {
    val initTime = LocalDateTime.now
    // Define one reaction site per time slice.
    val finalBoard = run3D(boardSize, maxTimeStep, initBoard, (tp, reactionMatrix) => reactionMatrix.foreach(timesliceReactionMatrix => site(tp)(timesliceReactionMatrix.flatten: _*)))
    val elapsed = initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)
    println(s"Test ($maxTimeStep reaction sites with ${boardSize.area} reactions each, 9 molecules) with $boardSize and $maxTimeStep timesteps took $elapsed ms. Final board at t=$maxTimeStep:")
    checkResult(finalBoard)
  }

  it should "6. run correctly using 3D reaction implementation with per-cell reaction sites" in {
    val initTime = LocalDateTime.now
    // Each cell (x,y,t) has 1 reaction at 1 reaction site. Maximum concurrency.
    val finalBoard = run3D(boardSize, maxTimeStep, initBoard, (tp, reactionMatrix) => reactionMatrix.foreach(_.foreach(_.foreach(r => site(tp)(r)))))
    val elapsed = initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)
    println(s"Test (${maxTimeStep * boardSize.area} reaction sites with 1 reaction each, 9 molecules each) with $boardSize and $maxTimeStep timesteps took $elapsed ms. Final board at t=$maxTimeStep:")
    checkResult(finalBoard)
  }

}
