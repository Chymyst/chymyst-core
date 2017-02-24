package io.chymyst.jc

import utest._
import CrossMoleculeSorting.{findFirstConnectedGroupSet, groupConnectedSets, sortedConnectedSets, getDSLProgram, Coll}

object CrossMoleculeSortingSpec extends TestSuite {
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
          result.length == 1,
          result(0)._1 == Set(),
          result(0)._2 sameElements Array[Set[Int]]()
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
    "get the molecule sequence for cross-groups only" - {

      def moleculeWeights(n: Int) = Array.tabulate[(Int, Boolean)](n)(i ⇒ (1, false))

      def moleculeWeightsIncr(n: Int) = Array.tabulate[(Int, Boolean)](n)(i ⇒ (-i, false))

      def getMolIndices(dsl: Seq[SearchDSL]): Seq[Int] = dsl.flatMap {
        case ChooseMolAndClose(i) ⇒ Some(i)
        case ChooseMol(i) ⇒ Some(i)
        case _ => None
      }

      def getDSL(cg: Array[Set[Int]], mw: Array[(Int, Boolean)]): List[SearchDSL] =
        getDSLProgram(cg, Array(), Array(), mw).toList

      "with monotonic molecule weights" - {
        * - {
          val crossGroups0 = Array(Set(0, 1), Set(3, 4), Set(0, 2))
          val result = getDSL(crossGroups0, moleculeWeightsIncr(5))
          assert(result == List(
            ChooseMol(4),
            ChooseMol(3),
            ConstrainGuard(1),
            CloseGroup,
            ChooseMol(2),
            ChooseMol(0),
            ConstrainGuard(2),
            ChooseMol(1),
            ConstrainGuard(0),
            CloseGroup
          ))
          assert(getMolIndices(result) == List(4, 3, 2, 0, 1))
        }
        * - {
          val crossGroups0 = Array(Set(0, 2), Set(0, 1), Set(3, 4))
          val result = getDSL(crossGroups0, moleculeWeightsIncr(5))
          assert(result == List(
            ChooseMol(4),
            ChooseMol(3),
            ConstrainGuard(2),
            CloseGroup,
            ChooseMol(1),
            ChooseMol(0),
            ConstrainGuard(1),
            ChooseMol(2),
            ConstrainGuard(0),
            CloseGroup
          ))
          assert(getMolIndices(result) == List(4, 3, 1, 0, 2))
        }
      }

      "from initial data" - {
        def getMS(cg: Array[Set[Int]]) = getDSL(cg, moleculeWeights(8))

        * - {
          val result = getMS(crossGroups1)
          assert(
            getMolIndices(result) == List(0, 6, 1, 7, 3, 4, 5, 2)
          )
          assert(// Array(Set(0, 1), Set(2, 3), Set(3, 4, 5), Set(0, 6), Set(6, 7))
            result == List(
              ChooseMol(0),
              ChooseMol(6),
              ConstrainGuard(3),
              ChooseMol(1),
              ConstrainGuard(0),
              ChooseMol(7),
              ConstrainGuard(4),
              CloseGroup,
              ChooseMol(3),
              ChooseMol(4),
              ChooseMol(5),
              ConstrainGuard(2),
              ChooseMol(2),
              ConstrainGuard(1),
              CloseGroup
            )
          )
        }
        * - {
          val result = getMS(crossGroups2)
          assert(
            getMolIndices(result) == List(6, 7, 0, 1, 3, 4, 5, 2)
          )
          assert(// Array(Set(0, 1), Set(2, 3), Set(3, 4, 5), Set(0, 6), Set(6, 7), Set(7, 1))
            result == List(
              ChooseMol(6),
              ChooseMol(7),
              ConstrainGuard(4),
              ChooseMol(0),
              ConstrainGuard(3),
              ChooseMol(1),
              ConstrainGuard(5),
              ConstrainGuard(0),
              CloseGroup,
              ChooseMol(3),
              ChooseMol(4),
              ChooseMol(5),
              ConstrainGuard(2),
              ChooseMol(2),
              ConstrainGuard(1),
              CloseGroup
            )
          )
        }
        * - {
          val result = getMS(crossGroups3)
          assert(getMolIndices(result) == List(3, 4, 5, 2, 7, 6, 0, 1))
          assert(
            result == List(
              ChooseMol(3),
              ChooseMol(4),
              ChooseMol(5),
              ConstrainGuard(2),
              ChooseMol(2),
              ConstrainGuard(1),
              ChooseMol(7),
              ConstrainGuard(5),
              ChooseMol(6),
              ConstrainGuard(4),
              ChooseMol(0),
              ConstrainGuard(3),
              ChooseMol(1),
              ConstrainGuard(0),
              CloseGroup
            )

          )
        }
      }

    }

  }
}
