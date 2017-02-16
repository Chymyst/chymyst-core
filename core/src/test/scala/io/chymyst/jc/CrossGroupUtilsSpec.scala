package io.chymyst.jc

import utest._
import CrossGroupUtils._

object CrossGroupUtilsSpec extends TestSuite {
  val tests = this {
    val crossGroups1 = Array(Array(0, 1), Array(2, 3), Array(3, 4, 5), Array(0, 6), Array(6, 7)).map(_.toSet)
    val crossGroups2 = Array(Array(0, 1), Array(2, 3), Array(3, 4, 5), Array(0, 6), Array(6, 7), Array(7, 1)).map(_.toSet)
    val crossGroups3 = Array(Array(0, 1), Array(2, 3), Array(3, 4, 5), Array(0, 6), Array(6, 7), Array(7, 2)).map(_.toSet)
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
          result(0)._2 sameElements Array(Set(0, 1), Set(6, 7), Set(0, 6)),
          result(1)._1 == Set(2, 3, 4, 5),
          result(1)._2 sameElements Array(Set(2, 3), Set(3, 4, 5))
        )
      }
      * - {
        val result = sortedConnectedSets(groupConnectedSets(crossGroups2))
        assert(
          result.length == 2,
          result(0)._1 == Set(0, 1, 6, 7),
          result(0)._2 sameElements Array(Set(0, 1), Set(0, 6), Set(7, 1), Set(6, 7)),
          result(1)._1 == Set(2, 3, 4, 5),
          result(1)._2 sameElements Array(Set(2, 3), Set(3, 4, 5))
        )
      }
      * - {
        val result = sortedConnectedSets(groupConnectedSets(crossGroups3))
        assert(
          result.length == 1,
          result(0)._1 == Set(0, 1, 2, 3, 4, 5, 6, 7),
          result(0)._2 sameElements Array(Set(0, 1), Set(0, 6), Set(6, 7), Set(7, 2), Set(2, 3), Set(3, 4, 5))
        )
      }
    }
  }
}
