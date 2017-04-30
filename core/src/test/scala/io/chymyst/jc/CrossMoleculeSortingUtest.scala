package io.chymyst.jc

import utest._
import CrossMoleculeSorting.{findFirstConnectedGroupSet, groupConnectedSets, sortedConnectedSets, getDSLProgram}

object CrossMoleculeSortingUtest extends TestSuite {
  val tests = this {
    val crossGroups1 = Array(Set(0, 1), Set(2, 3), Set(3, 4, 5), Set(0, 6), Set(6, 7))
    val crossGroups2 = Array(Set(0, 1), Set(2, 3), Set(3, 4, 5), Set(0, 6), Set(6, 7), Set(7, 1))
    val crossGroups3 = Array(Set(0, 1), Set(2, 3), Set(3, 4, 5), Set(0, 6), Set(6, 7), Set(7, 2))
    "find first split group by detecting connected subsets" - {
      "empty array gives valid results" - {
        val result = findFirstConnectedGroupSet(Array())
        assert(
          result._1 == Set(),
          result._2 sameElements Array[Set[Int]](),
          result._3 sameElements Array[Set[Int]]()
        )
      }
      * - {
        val result = findFirstConnectedGroupSet(crossGroups1)
        assert(
          result._1 == Set(0, 1, 6, 7),
          result._2 sameElements Array(Set(0, 1), Set(0, 6), Set(6, 7)),
          result._3 sameElements Array(Set(2, 3), Set(3, 4, 5))
        )
      }
      * - {
        val result = findFirstConnectedGroupSet(crossGroups2)
        assert(
          result._1 == Set(0, 1, 6, 7),
          result._2 sameElements Array(Set(0, 1), Set(0, 6), Set(7, 1), Set(6, 7)),
          result._3 sameElements Array(Set(2, 3), Set(3, 4, 5))
        )
      }
      * - {
        val result = findFirstConnectedGroupSet(Array(Set(2, 3), Set(3, 4, 5)))
        assert(
          result._1 == Set(2, 3, 4, 5),
          result._2 sameElements Array(Set(2, 3), Set(3, 4, 5)),
          result._3 sameElements Array[Set[Int]]()
        )
      }
      * - {
        val result = findFirstConnectedGroupSet(crossGroups3)
        assert(
          result._1 == Set(0, 1, 2, 3, 4, 5, 6, 7),
          result._2 sameElements Array(Set(0, 1), Set(0, 6), Set(6, 7), Set(7, 2), Set(2, 3), Set(3, 4, 5)),
          result._3 sameElements Array[Set[Int]]()
        )
      }
    }
    "split molecule groups into connected subsets" - {
      "empty array gives valid results" - {
        val result = groupConnectedSets(Array())
        assert(
          result.length == 0
        )
      }
      * - {
        val result = groupConnectedSets(crossGroups1)
        assert(
          result.length == 2,
          result(0)._1 == Set(0, 1, 6, 7),
          result(0)._2 sameElements Array(Set(0, 1), Set(0, 6), Set(6, 7)),
          result(1)._1 == Set(2, 3, 4, 5),
          result(1)._2 sameElements Array(Set(2, 3), Set(3, 4, 5))
        )
      }
      * - {
        val result = groupConnectedSets(crossGroups2)
        assert(
          result.length == 2,
          result(0)._1 == Set(0, 1, 6, 7),
          result(0)._2 sameElements Array(Set(0, 1), Set(0, 6), Set(7, 1), Set(6, 7)),
          result(1)._1 == Set(2, 3, 4, 5),
          result(1)._2 sameElements Array(Set(2, 3), Set(3, 4, 5))
        )
      }
      * - {
        val result = groupConnectedSets(crossGroups3)
        assert(
          result.length == 1,
          result(0)._1 == Set(0, 1, 2, 3, 4, 5, 6, 7),
          result(0)._2 sameElements Array(Set(0, 1), Set(0, 6), Set(6, 7), Set(7, 2), Set(2, 3), Set(3, 4, 5))
        )
      }
    }
    "sort the connected subsets" - {
      "empty array gives empty result" - {
        assert(sortedConnectedSets(groupConnectedSets(Array())).length == 0)
      }
      * - {
        val result = sortedConnectedSets(groupConnectedSets(crossGroups1))
        assert(
          result.length == 2,
          result(0)._1 == Set(0, 1, 6, 7),
          result(0)._2 sameElements Array(Set(0, 6), Set(0, 1), Set(6, 7)),
          result(1)._1 == Set(2, 3, 4, 5),
          result(1)._2 sameElements Array(Set(3, 4, 5), Set(2, 3))
        )
      }
      * - {
        val result = sortedConnectedSets(groupConnectedSets(crossGroups2))
        assert(
          result.length == 2,
          result(0)._1 == Set(0, 1, 6, 7),
          result(0)._2 sameElements Array(Set(6, 7), Set(0, 6), Set(7, 1), Set(0, 1)),
          result(1)._1 == Set(2, 3, 4, 5),
          result(1)._2 sameElements Array(Set(3, 4, 5), Set(2, 3))
        )
      }
      * - {
        val result = sortedConnectedSets(groupConnectedSets(crossGroups3))
        assert(
          result.length == 1,
          result(0)._1 == Set(0, 1, 2, 3, 4, 5, 6, 7),
          result(0)._2 sameElements Array(Set(3, 4, 5), Set(2, 3), Set(7, 2), Set(6, 7), Set(0, 6), Set(0, 1))
        )
      }
    }

    "get the molecule sequence" - {

      def moleculeWeights(n: Int) = Array.tabulate[(Int, Boolean)](n)(i ⇒ (1, false))

      def moleculeWeightsIncr(n: Int) = Array.tabulate[(Int, Boolean)](n)(i ⇒ (-i, false))

      def getMolIndices(dsl: Seq[SearchDSL]): Seq[Int] = dsl.flatMap {
        case ChooseMol(i) ⇒ Some(i)
        case _ => None
      }

      "for cross-groups only" - {

        def getDSL(cg: Array[Set[Int]], mw: Array[(Int, Boolean)]): List[SearchDSL] = getDSLProgram(cg, Array(), mw).toList

        def printDSL(cg: Array[Set[Int]], dsl: List[SearchDSL]): String = dsl.map {
          case ChooseMol(i) ⇒ i.toString
          case CloseGroup ⇒ "<>"
          case ConstrainGuard(i) ⇒ "[" + cg(i).toList.sorted.mkString(",") + "]"
        }.mkString(" ")

        "with monotonic molecule weights" - {
          * - {
            val crossGroups0 = Array(Set(0, 1), Set(3, 4), Set(0, 2))
            val result = getDSL(crossGroups0, moleculeWeightsIncr(5))
            assert(printDSL(crossGroups0, result) == "4 3 [3,4] <> 1 0 [0,1] 2 [0,2] <>")
            assert(getMolIndices(result) == List(4, 3, 1, 0, 2))
          }
          * - {
            val crossGroups0 = Array(Set(0, 2), Set(0, 1), Set(3, 4))
            val result = getDSL(crossGroups0, moleculeWeightsIncr(5))
            assert(printDSL(crossGroups0, result) == "4 3 [3,4] <> 2 0 [0,2] 1 [0,1] <>")
            assert(getMolIndices(result) == List(4, 3, 2, 0, 1))
          }
        }

        "from initial data" - {
          def getMS(cg: Array[Set[Int]]) = getDSL(cg, moleculeWeights(8))

          * - {
            val result = getMS(crossGroups1)
            assert(
              getMolIndices(result) == List(6, 7, 0, 1, 2, 3, 4, 5)
            )
            assert(// crossGroups1 = Array(Set(0, 1), Set(2, 3), Set(3, 4, 5), Set(0, 6), Set(6, 7))
              printDSL(crossGroups1, result) == "6 7 [6,7] 0 [0,6] 1 [0,1] <> 2 3 [2,3] 4 5 [3,4,5] <>")
          }
          * - {
            val result = getMS(crossGroups2)
            assert(getMolIndices(result) == List(0, 1, 7, 6, 2, 3, 4, 5))
            assert(// crossGroups2 = Array(Set(0, 1), Set(2, 3), Set(3, 4, 5), Set(0, 6), Set(6, 7), Set(7, 1))
              printDSL(crossGroups2, result) == "0 1 [0,1] 7 [1,7] 6 [0,6] [6,7] <> 2 3 [2,3] 4 5 [3,4,5] <>")
          }
          * - {
            val result = getMS(crossGroups3)
            assert(getMolIndices(result) == List(0, 1, 6, 7, 2, 3, 4, 5))
            assert( // crossGroups 3 = Array(Set(0, 1), Set(2, 3), Set(3, 4, 5), Set(0, 6), Set(6, 7), Set(7, 2))
              printDSL(crossGroups3, result) == "0 1 [0,1] 6 [0,6] 7 [6,7] 2 [2,7] 3 [2,3] 4 5 [3,4,5] <>")
          }
        }

        "from reactions" - {
          "Game of Life" - {
            case class Cell(x: Int, y: Int, t: Int, state: Int, label: (Int, Int))

            Cell(0,0,0,0,(0,0))

            val c = m[Cell]

            val reaction = go { case
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
                t0 == t1 && t0 == t2 && t0 == t3 && t0 == t4 && t0 == t5 && t0 == t6 && t0 == t7 && t0 == t8 ⇒
            }
            val dsl = printDSL(reaction.info.crossGuards.map(_.indices.toSet), reaction.info.searchDSLProgram.toList)
            assert(dsl == "0 8 [0,8] 7 [0,7] 6 [0,6] 5 [0,5] 4 [0,4] 3 [0,3] 2 [0,2] 1 [0,1] <>")
          }

          "8 queens" - {

            def safe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
              x1 != x2 && y1 != y2 && (x1 - y1 != x2 - y2) && (x1 + y1 != x2 + y2)

            val pos = m[(Int, Int)]
            val reaction = go {
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
                  safe(x7, y7, x8, y8) ⇒
            }
            val dsl = printDSL(reaction.info.crossGuards.map(_.indices.toSet), reaction.info.searchDSLProgram.toList)
            assert(dsl == "6 7 [6,7] 5 [5,6] [5,7] 4 [4,7] [4,5] [4,6] 3 [3,6] [3,7] [3,4] [3,5] 2 [2,5] [2,4] [2,6] [2,3] [2,7] 1 [1,3] [1,5] [1,6] [1,4] [1,2] [1,7] 0 [0,1] [0,7] [0,2] [0,5] [0,3] [0,6] [0,4] <>")
          }
        }

      }
    }

  }
}
