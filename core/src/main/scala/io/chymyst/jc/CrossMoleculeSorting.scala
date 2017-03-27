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

  // Insert ConstrainGuard(i) commands whenever the already chosen molecules contain the set constrained by the guard.
  private def insertConstrainGuardCommands(crossGroups: Coll[Set[Int]], program: Coll[SearchDSL]): Coll[SearchDSL] = {
    val groupIndexed = crossGroups.zipWithIndex
    // Accumulator is a 4-tuple: (all guards seen so far, current guards, all molecules seen so far, current command)
    program.scanLeft((Set[Int](), Set[Int](), Set[Int](), CloseGroup: SearchDSL)) {
      // If the current command is ChooseMol() then we need to update the sets of molecules and guards.
      case ((oldAllGuards, _, oldAvailableMolecules, _), c@ChooseMol(i)) ⇒
        val newAvailableMolecules = oldAvailableMolecules + i
        val newAllGuards = groupIndexed.filter(_._1.subsetOf(newAvailableMolecules)).map(_._2).toSet
        val currentGuards = newAllGuards diff oldAllGuards
        (newAllGuards, currentGuards, newAvailableMolecules, c)
      // If the current command is any other `c`, we need to simply insert it into the tuple, with empty set of guards.
      case ((a, _, s, _), c) ⇒
        (a, Set[Int](), s, c)
    }
      .drop(1) // scanLeft produces an initial element, which we don't need.
      // Retain only the sets of currentGuards; transform into ConstrainGuard(i).
      .flatMap { case (_, g, _, c) ⇒ Array(c) ++ g.toArray.map(ConstrainGuard) }
  }

  private[jc] def getDSLProgram(
    crossGroups: Coll[Set[Int]],
    repeatedMols: Coll[Set[Int]],
    moleculeWeights: Coll[(Int, Boolean)]
  ): Coll[SearchDSL] = {
    val allGroups: Coll[Set[Int]] = crossGroups ++ repeatedMols

    val sortedCrossGroups: Coll[Coll[Coll[Int]]] = sortedConnectedSets(groupConnectedSets(allGroups))
      .map(_._2.map(_.toArray.sortBy(moleculeWeights.apply))) // each cross-guard set needs to be sorted by molecule weight

    val programWithoutConstrainGuardCommands: Coll[SearchDSL] =
      sortedCrossGroups.flatMap(
        // We reverse the order so that smaller groups go first, which may help optimize the guard positions.
        _.reverse
          .flatMap(_.map(ChooseMol)).distinct :+ CloseGroup
      )

    insertConstrainGuardCommands(crossGroups, programWithoutConstrainGuardCommands)
  }

}

/** Commands used while searching for molecule values among groups of input molecules that are constrained by cross-molecule guards or conditionals.
  * A sequence of these commands (the "SearchDSL program") is computed for each reaction by the reaction site.
  * SearchDSL programs are interpreted at run time by [[Reaction.findInputMolecules]].
  */
private[jc] sealed trait SearchDSL

/** Choose a molecule value among the molecules available at the reaction site.
  *
  * @param i Index of the molecule within the reaction input list (the "input index").
  */
private[jc] final case class ChooseMol(i: Int) extends SearchDSL

/** Impose a guard condition on the molecule values found so far.
  *
  * @param i Index of the guard in `crossGuards` array
  */
private[jc] final case class ConstrainGuard(i: Int) extends SearchDSL

/** A group of cross-dependent molecules has been closed.
  * At this point, we can select one set of molecule values and stop searching for other molecule values within this group.
  * If no molecule values are found that satisfy all constraints for this group, the search for the molecule values can be abandoned (the current reaction cannot run).
  */
private[jc] case object CloseGroup extends SearchDSL
