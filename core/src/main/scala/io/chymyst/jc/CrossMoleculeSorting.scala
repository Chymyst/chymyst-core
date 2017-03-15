package io.chymyst.jc

import scala.annotation.tailrec

/** Utility functions for various calculations related to cross-molecule guards and conditions.
  * Molecules are represented by their zero-based indices in the reaction input list. (These are not the site-wide molecule indices.)
  */
private[jc] object CrossMoleculeSorting {

  private[jc] type Coll[T] = Array[T] // This type can be easily changed to another ordered collection such as `IndexedSeq` if that proves to be better.
  // There are only a few usages of Array() and Array(x) below.

  @tailrec
  private def sortConnectedSets(connectedSets: Coll[Set[Int]], result: Coll[Set[Int]] = Array()): Coll[Set[Int]] = {
    connectedSets.lastOption match {
      case None ⇒
        result
      case Some(largest) ⇒
        val (connected, nonConnected) = connectedSets.partition(_.exists(largest.contains))
        sortConnectedSets(nonConnected, result ++ connected.sortBy(g ⇒ (-(largest intersect g).size, g.min, g.max)))
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
    if (currentSet.isEmpty)
      result
    else {
      val newResult = result ++ Array((currentSet, currentResult))
      if (remaining.isEmpty)
        newResult
      else
        groupConnectedSets(remaining, newResult)
    }
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

  private[jc] def getDSLProgram(
    crossGroups: Coll[Set[Int]],
    repeatedMols: Coll[Set[Int]],
    moleculeWeights: Coll[(Int, Boolean)]
  ): Coll[SearchDSL] = {
    val allGroups: Coll[Set[Int]] = crossGroups ++ repeatedMols

    def crossGroupsIndexed: Coll[(Set[Int], Int)] = allGroups.zipWithIndex

    def getDSLProgramForCrossGroup(group: Coll[Int]): IndexedSeq[SearchDSL] = {
      val guardConditionIndices = crossGroupsIndexed
        .filter(_._1 equals group.toSet) // find all guard conditions that correspond to this group (could be > 1 due to cross-conditionals)
        .map(_._2)
        .filter(_ < crossGroups.length) // only use indices for cross-molecule guards, not for repeated molecule groups

      group.map(ChooseMol) ++ guardConditionIndices.map(ConstrainGuard)
    }

    val sortedCrossGroups: Coll[Coll[Coll[Int]]] = sortedConnectedSets(groupConnectedSets(allGroups))
      .map(_._2.map(_.toArray.sortBy(moleculeWeights.apply))) // each cross-guard set needs to be sorted by molecule weight

    sortedCrossGroups.flatMap(_.flatMap(getDSLProgramForCrossGroup).distinct :+ CloseGroup)
  }

}

private[jc] sealed trait SearchDSL

private[jc] final case class ChooseMol(i: Int) extends SearchDSL

/** Impose a guard condition on the molecule values found so far.
  *
  * @param i Index of the guard in `crossGuards` array
  */
private[jc] final case class ConstrainGuard(i: Int) extends SearchDSL

private[jc] case object CloseGroup extends SearchDSL
