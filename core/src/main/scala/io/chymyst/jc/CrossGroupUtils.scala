package io.chymyst.jc

import scala.annotation.tailrec

/** Utility functions for various calculations related to cross-molecule guards and conditions.
  *
  */
object CrossGroupUtils {

  def crossGroupsGrouped(allCrossGroups: Set[Set[Int]]): Array[(Set[Int], Array[Array[Int]])] = {
    allCrossGroups.foldLeft((Set[Int](), IndexedSeq[Array[Int]](), Array[(Set[Int], Array[Array[Int]])]())) { case ((currentSet, currentGroups, currentResult), group) ⇒
      if (currentSet.isEmpty) // we are at the very beginning of `foldLeft`
        (group, IndexedSeq(group), currentResult)
      else {
        // If the new group does not intersect any of the sets so far, it's a new
      }
      ???
    }._3
  }

  @tailrec
  def groupConnectedSets(
    allGroups: Array[Set[Int]],
    result: Array[(Set[Int], Array[Set[Int]])] = Array()
  ): Array[(Set[Int], Array[Set[Int]])] = {
    val (currentSet, currentResult, remaining) = findFirstConnectedGroupSet(allGroups)
    val newResult = result ++ Array((currentSet, currentResult))
    if (remaining.isEmpty)
      newResult
    else
      groupConnectedSets(remaining, newResult)
  }

  @tailrec
  def findFirstConnectedGroupSet(
    allGroups: Array[Set[Int]],
    currentSet: Set[Int] = Set(),
    result: Array[Set[Int]] = Array()
  ): (Set[Int], Array[Set[Int]], Array[Set[Int]]) = {
    allGroups.headOption match {
      case None ⇒
        (currentSet, result, allGroups)
      case Some(firstGroup) ⇒
        // `allGroups` is non-empty
        val effectiveCurrentSet = if (currentSet.isEmpty)
          firstGroup
        else
          currentSet
        val (intersecting, nonIntersecting) = allGroups.partition(_.exists(effectiveCurrentSet.contains))
        if (intersecting.isEmpty)
          (currentSet, result, nonIntersecting)
        else
          findFirstConnectedGroupSet(nonIntersecting, effectiveCurrentSet ++ intersecting.flatten, result ++ intersecting)
    }
  }

  //  private val crossGroupsSortedByComplexityGain: Array[Array[Int]] = {
  //    // sort by the metric: the total number of common members with the largest group
  //    // those groups that have no common members with the largest one should be sorted again recursively
  //    @tailrec
  //    def sortByComplexityGain(result: Array[Array[Int]], groups: Array[Array[Int]]): Array[Array[Int]] = {
  //      if (groups.isEmpty) groups
  //      else {
  //        val largestGroup = groups.maxBy(_.length)
  //
  //        // Some groups have an intersection with the largest group, others don't.
  //        val (touched, untouched) = groups.partition(group ⇒ (largestGroup intersect group).nonEmpty)
  //        val touchedSorted = touched.sortBy(arr ⇒ (-arr.intersect(largestGroup).length / arr.length, arr.min, arr.max))
  //        if (untouched.isEmpty)
  //          result ++ touchedSorted
  //        else
  //          sortByComplexityGain(result ++ touchedSorted, untouched)
  //      }
  //    }
  //
  //    sortByComplexityGain(new Array(0), allCrossGroups).map(_.sorted)
  //  }

}
