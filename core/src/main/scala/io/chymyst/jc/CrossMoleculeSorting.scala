package io.chymyst.jc

import scala.annotation.tailrec

/** Utility functions for various calculations related to cross-molecule guards and conditions.
  * Molecules are represented by their zero-based indices in the reaction input list. (These are not the site-wide molecule indices.)
  */
private[jc] object CrossMoleculeSorting {

  private[jc] type Coll[T] = Array[T] // This type can be easily changed to another ordered collection such as `IndexedSeq` if that proves to be better.
  // There are only a few usages of Array() and Array(x) below.

  private[jc] def getMoleculeSequence(crossGroups: Coll[Set[Int]], moleculeWeights: Coll[(Int, Boolean)]): Coll[Int] =
    getMoleculeSequenceFromSorted(sortedConnectedSets(groupConnectedSets(crossGroups)).flatMap(_._2), moleculeWeights)

  private[jc] def getMoleculeSequenceFromSorted(sortedSets: Coll[Set[Int]], moleculeWeights: Coll[(Int, Boolean)]): Coll[Int] =
    sortedSets
      .flatMap { group ⇒ group.toArray.sortBy { i ⇒ (-moleculeWeights(i)._1, moleculeWeights(i)._2) } }
      .distinct

  @tailrec
  private[jc] def sortConnectedSets(connectedSets: Coll[Set[Int]], result: Coll[Set[Int]] = Array()): Coll[Set[Int]] = {
    connectedSets.lastOption match {
      case None ⇒
        result
      case Some(largest) ⇒
        val (connected, nonConnected) = connectedSets.partition(_.exists(largest.contains))
        sortConnectedSets(nonConnected, result ++ connected)
    }
  }

  private[jc] def sortedConnectedSets(groupedSets: Coll[(Set[Int], Coll[Set[Int]])]): Coll[(Set[Int], Coll[Set[Int]])] =
    groupedSets
      .sortBy(_._1.size)
      .map { case (s, a) ⇒
        (s, sortConnectedSets(a.sortBy { g ⇒ a.map(_ intersect g).map(_.size).sum }))
      }

  @tailrec
  private[jc] def groupConnectedSets(
    allGroups: Coll[Set[Int]],
    result: Coll[(Set[Int], Coll[Set[Int]])] = Array()
  ): Coll[(Set[Int], Coll[Set[Int]])] = {
    val (currentSet, currentResult, remaining) = findFirstConnectedGroupSet(allGroups)
    val newResult = result ++ Array((currentSet, currentResult))
    if (remaining.isEmpty)
      newResult
    else
      groupConnectedSets(remaining, newResult)
  }

  @tailrec
  private[jc] def findFirstConnectedGroupSet(
    allGroups: Coll[Set[Int]],
    currentSet: Set[Int] = Set(),
    result: Coll[Set[Int]] = Array()
  ): (Set[Int], Coll[Set[Int]], Coll[Set[Int]]) = {
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

}
