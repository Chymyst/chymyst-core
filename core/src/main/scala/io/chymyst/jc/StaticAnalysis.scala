package io.chymyst.jc

import io.chymyst.jc.Core._

import scala.annotation.tailrec
import scalaxy.streams.optimize
import scalaxy.streams.strategy.aggressive

private[jc] object StaticAnalysis {

  /** Check that every input molecule matcher of one reaction is weaker than a corresponding input matcher in another reaction.
    * If true, it means that the first reaction can start whenever the second reaction can start, which is an instance of unavoidable indeterminism.
    *
    * @param input1 Sorted input list for the first reaction.
    * @param input2 Sorted input list  for the second reaction.
    * @return True if the first reaction is weaker than the second.
    */
  @tailrec
  private def allMatchersAreWeakerThan(input1: List[InputMoleculeInfo], input2: List[InputMoleculeInfo]): Boolean = {
    input1 match {
      case Nil =>
        true // input1 has no matchers left
      case info1 :: rest1 => input2 match {
        case Nil =>
          false // input1 has matchers but input2 has no matchers left
        case _ =>
          // find a molecule value matcher that is stronger than info1
          input2.find(i => info1.matcherIsWeakerThan(i).getOrElse(false)) match {
            case Some(correspondingMatcher) =>
              allMatchersAreWeakerThan(rest1, input2 difff List(correspondingMatcher))
            case None => false
          }
      }
    }
  }

  private def inputMatchersWeakerThanOutput(isWeaker: (InputMoleculeInfo, OutputMoleculeInfo) => Option[Boolean])
    (input: List[InputMoleculeInfo], output: Array[OutputMoleculeInfo]): Boolean = {
    input.flatFoldLeft(output) { (acc, inputInfo) ⇒
      acc
        .find(outputInfo => isWeaker(inputInfo, outputInfo).getOrElse(false))
        .map { correspondingMatcher => acc diff Array(correspondingMatcher) }
    }.nonEmpty
  }

  private def inputMatchersSurelyWeakerThanOutput(input: List[InputMoleculeInfo], output: Array[OutputMoleculeInfo]): Boolean =
    inputMatchersWeakerThanOutput((inputInfo, outputInfo) => inputInfo.matcherIsWeakerThanOutput(outputInfo))(input, output.filter(_.atLeastOnce))

  private def inputMatchersAreSimilarToOutput(input: List[InputMoleculeInfo], output: Array[OutputMoleculeInfo]): Boolean =
    inputMatchersWeakerThanOutput((inputInfo, outputInfo) => inputInfo.matcherIsSimilarToOutput(outputInfo))(input, output)

  // Reactions whose inputs are all unconditional matchers and are a subset of inputs of another reaction:
  private def checkReactionShadowing(reactions: Array[Reaction]): Option[String] = optimize {
    val suspiciousReactions = for {
      r1 <- reactions
      if r1.info.guardPresence.noCrossGuards
      r2 <- reactions
      if r2 =!= r1 && r1.inputMoleculesSet.subsetOf(r2.inputMoleculesSet) &&
        allMatchersAreWeakerThan(r1.info.inputsSortedByConstraintStrength, r2.info.inputsSortedByConstraintStrength)
    } yield (r1, r2)

    if (suspiciousReactions.nonEmpty) {
      val errorList = suspiciousReactions.map { case (r1, r2) =>
        s"reaction {${r2.info}} is shadowed by {${r1.info}}"
      }.mkString(", ")
      Some(s"Unavoidable indeterminism: $errorList")
    } else None
  }

  // There should not be any two reactions whose source code is identical to each other.
  private def findIdenticalReactions(reactions: Array[Reaction]): Option[String] = optimize {
    val reactionsSha1 = reactions.map(_.inputInfoSha1)
    val repeatedReactionSha1 = (reactionsSha1 diff reactionsSha1.distinct).distinct
    val repeatedReactions = repeatedReactionSha1.flatMap(sha1 ⇒ reactions.filter(_.inputInfoSha1 === sha1))

    if (repeatedReactions.nonEmpty) {
      val errorList = repeatedReactions.map { r => s"{${r.info}}" }.mkString(", ")
      Some(s"Identical repeated reactions: $errorList")
    } else None
  }

  private def checkSingleReactionLivelock(reactions: Array[Reaction]): Option[String] = optimize {
    val errorList = reactions
      .filter { r => r.info.guardPresence.noCrossGuards && inputMatchersSurelyWeakerThanOutput(r.info.inputsSortedByConstraintStrength, r.info.shrunkOutputs) }
      .map(r => s"{${r.info.toString}}")
    if (errorList.nonEmpty)
      Some(s"Unavoidable livelock: reaction${if (errorList.length === 1) "" else "s"} ${errorList.mkString(", ")}")
    else None
  }

  private def checkMultiReactionLivelock(reactions: Array[Reaction]): Option[String] = {
    // TODO: implement
    None
  }

  private[jc] def findDistributedRSErrors(reactionSite: ReactionSite): Seq[String] = {
    val nonSingleInstance = if (reactionSite.isDistributed && !reactionSite.isSingleInstance)
      Seq(s"Non-single-instance reaction site may not consume distributed molecules, but found molecule(s) ${reactionSite.knownDMs.keys.mkString(", ")}")
    else Nil
    val nonSingleCluster = if (reactionSite.knownInputMolecules.keys
      .collect { case dm: DM[_] ⇒ dm.clusterConfig }
      .toSet.size > 1)
      Seq(s"All input distributed molecules must belong to the same cluster, but found molecule(s) ${reactionSite.knownDMs.keys.mkString(", ")}")
    else Nil
    nonSingleInstance ++ nonSingleCluster
  }

  private def unboundMoleculeWarning(reactions: Array[Reaction]): Option[String] = {
    val warningList = unboundOutputMoleculesString(reactions)
    if (warningList.nonEmpty)
      Some(s"Output molecules ($warningList) are still not bound when this reaction site is created")
    else None
  }

  private def singleReactionLivelockWarning(reactions: Array[Reaction]): Option[String] = optimize {
    val warningList = reactions
      .filter { r => inputMatchersAreSimilarToOutput(r.info.inputsSortedByConstraintStrength, r.info.shrunkOutputs) }
      .map(r => s"{${r.info.toString}}")
    if (warningList.nonEmpty)
      Some(s"Possible livelock: reaction${if (warningList.length === 1) "" else "s"} ${warningList.mkString(", ")}")
    else None
  }

  // This is very slow!
  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  private def checkInputsForDeadlockWarning(reactions: Array[Reaction]): Option[String] = optimize {
    // A "possible deadlock" means that an input blocking molecule is consumed together with other molecules that are emitted later by the same reactions that emit the blocking molecule.

    val warningList = for {
      re ← reactions
      bmInputs = re.info.inputsSortedByConstraintStrength.partition(_.molecule.isBlocking)
      bInput ← bmInputs._1
      mInput ← bmInputs._2
      possibleReactions = bInput.molecule.emittingReactions ++ mInput.molecule.emittingReactions
      reaction ← possibleReactions
      outputs = reaction.info.outputs
      if outputs.nonEmpty
      outputMolecules = outputs.map(_.molecule)
      // Find a reaction that first emits bInput and then emits mInput, with stronger matchers than bInput and mInput respectively.
      if outputMolecules.contains(bInput.molecule)
      bInputIndex = outputMolecules.indexOf(bInput.molecule)
      mInputIndex = outputMolecules.indexOf(mInput.molecule)
      if mInputIndex > bInputIndex
      if bInput.matcherIsSimilarToOutput(outputs(bInputIndex)).getOrElse(false)
      if mInput.matcherIsSimilarToOutput(outputs(mInputIndex)).getOrElse(false)
    } yield {
      s"molecule (${bInput.molecule}) may deadlock due to (${mInput.molecule}) among the outputs of {${reaction.info}}"
    }

    if (warningList.nonEmpty)
      Some(s"Possible deadlock${if (warningList.length === 1) "" else "s"}: ${warningList.mkString("; ")}")
    else None
  }

  private def checkOutputsForDeadlockWarning(reactions: Array[Reaction]): Option[String] = optimize {
    // A "possible deadlock" means that an output blocking molecule is followed by other output molecules that the blocking molecule may be waiting for.
    val possibleDeadlocks: Array[(OutputMoleculeInfo, List[OutputMoleculeInfo])] =
      reactions.map(_.info.outputs)
        .flatMap {
          _.tails.flatMap(_.toList match {
            case t :: ts => if (t.molecule.isBlocking) Some((t, ts)) else None
            case Nil => None
          })
        }
    // The chemistry is likely to be a deadlock if at least one the other output molecules are consumed together with the blocking molecule in the same reaction.
    val likelyDeadlocks = possibleDeadlocks.map {
      case (info, infos) =>
        (info, info.molecule.consumingReactions
          .find { r =>
            // For each reaction that consumes the molecule `info.molecule`, check whether this reaction also consumes
            // any of the molecules from infos.map(_.molecule). If so, it's a likely deadlock.
            val uniqueInputsThatAreAmongOutputs = r.info.inputsSortedByConstraintStrength
              .filter(infos.map(_.molecule) contains _.molecule)
              .groupBy(_.molecule).mapValues(_.lastOption).values.flatten // Among repeated input molecules, choose only one molecule with the weakest matcher.

            uniqueInputsThatAreAmongOutputs.exists(infoInput =>
              infos.exists(infoOutput =>
                infoInput.matcherIsWeakerThanOutput(infoOutput).getOrElse(false))
            )
          }
        )
    }

    val warningList = likelyDeadlocks
      .flatMap { case (info, reactionOpt) => reactionOpt.map(r => s"molecule ${info.molecule} may deadlock due to outputs of {${r.info}}") }
    if (warningList.nonEmpty)
      Some(s"Possible deadlock${if (warningList.length === 1) "" else "s"}: ${warningList.mkString("; ")}")
    else None
  }

  private[jc] def findShadowingErrors(reactions: Array[Reaction]) =
    Seq(
      checkReactionShadowing _
    ).flatMap(_ (reactions))

  private[jc] def findGeneralErrors(reactions: Array[Reaction]) = {
    Seq(
      findIdenticalReactions _,
      checkSingleReactionLivelock _,
      checkMultiReactionLivelock _
    ).flatMap(_ (reactions))
  }

  private[jc] def findGeneralWarnings(reactions: Array[Reaction]) = {
    Seq(
      checkOutputsForDeadlockWarning _,
      checkInputsForDeadlockWarning _,
      unboundMoleculeWarning _,
      singleReactionLivelockWarning _
    ).flatMap(_ (reactions))
  }

  private def checkStaticMolsNotDistributed(staticMols: Map[MolEmitter, Int], reactions: Array[Reaction]): Option[String] = optimize {
    val errorList = staticMols.keySet
      .filter(_.isDistributed)
      .map(_.toString)
    if (errorList.nonEmpty)
      Some(s"Distributed molecules may not be declared static, but found such molecule(s): ${errorList.mkString(", ")}")
    else None
  }

  // Each static molecule must occur in some reaction as an input.
  // No static molecule should be consumed twice by a reaction.
  // Each static molecule that is consumed by a reaction should also be emitted by the same reaction.
  private def checkInputsForStaticMols(staticMols: Map[MolEmitter, Int], reactions: Array[Reaction]): Option[String] = optimize {
    val staticMolsConsumedMaxTimes: Map[MolEmitter, Option[(Reaction, Int)]] =
      staticMols.map { case (mol, _) ⇒
        val reactionsWithCounts = if (reactions.isEmpty)
          None
        else
          Some(reactions.map(r => (r, r.inputMoleculesSortedAlphabetically.count(_ === mol))).maxBy(_._2))
        mol → reactionsWithCounts
      }

    val onlyStaticConsumed = reactions
      .filter(_.info.inputs.forall(info ⇒ staticMols.contains(info.molecule)))
      .map { r ⇒ s"reaction {${r.info}} has only static input molecules (${r.inputMoleculesSortedAlphabetically.mkString(",")})" }

    val wrongConsumed = staticMolsConsumedMaxTimes
      .flatMap {
        case (mol, None) ⇒
          Some(s"static molecule ($mol) not consumed by any reactions")
        case (mol, Some((_, 0))) ⇒
          Some(s"static molecule ($mol) not consumed by any reactions")
        case (_, Some((_, 1))) ⇒
          None
        case (mol, Some((reaction, countConsumed))) =>
          Some(s"static molecule ($mol) consumed $countConsumed times by reaction {${reaction.info}}")
      }

    val wrongOutput = staticMols.map {
      case (mol, _) => mol -> reactions.find(r => r.inputMoleculesSortedAlphabetically.count(_ === mol) === 1 && !r.info.outputs.exists(_.molecule === mol))
    }.flatMap {
      case (mol, Some(r)) =>
        Some(s"static molecule ($mol) consumed but not emitted by reaction {${r.info}}")
      case _ => None
    }

    val errorList = wrongConsumed ++ wrongOutput ++ onlyStaticConsumed

    if (errorList.nonEmpty)
      Some(s"Incorrect static molecule usage: ${errorList.mkString("; ")}")
    else None
  }

  // If a static molecule is output by a reaction, the same reaction must consume that molecule.
  // Every static molecule should be output exactly once and in a once-only output environment.
  private def checkOutputsForStaticMols(staticMols: Map[MolEmitter, Int], reactions: Array[Reaction]): Option[String] = optimize {
    val errorList = staticMols.flatMap {
      case (mol, _) =>
        reactions.flatMap {
          r =>
            val outputs = r.info.shrunkOutputs.filter(_.molecule === mol)
            val outputTimes = outputs.length
            val containsAsInput = r.inputMoleculesSet.contains(mol)
            if (outputTimes > 1)
              Some(s"static molecule ($mol) emitted more than once by reaction {${
                r.info
              }}")
            else if (outputs.count(_.environments.forall(env ⇒ env.linear && env.atLeastOne)) === 1) {
              if (!containsAsInput)
                Some(s"static molecule ($mol) emitted but not consumed by reaction {${
                  r.info
                }}")
              else None
            } else if (outputTimes =!= 0 && containsAsInput) // outputTimes == 0 was already handled by checkInputsForStaticMols
              Some(s"static molecule ($mol) consumed but not guaranteed to be emitted by reaction {${
                r.info
              }}")
            else None
        }
    }

    if (errorList.nonEmpty)
      Some(s"Incorrect static molecule usage: ${
        errorList.mkString("; ")
      }")
    else None
  }

  private[jc] def findStaticMolErrors(staticMols: Map[MolEmitter, Int], reactions: Array[Reaction]) = {
    Seq(
      checkStaticMolsNotDistributed _,
      checkOutputsForStaticMols _,
      checkInputsForStaticMols _
    ).flatMap(_ (staticMols, reactions))
  }

  private[jc] def findStaticMolWarnings(staticMols: Map[MolEmitter, Int], reactions: Array[Reaction]) = {
    // TODO: implement
    Seq()
  }

  private[jc] def findStaticMolDeclarationErrors(staticReactions: Array[Reaction]): Seq[String] = {
    val foundErrors = staticReactions.map(_.info).filterNot(_.guardPresence.noCrossGuards)
    if (foundErrors.nonEmpty)
      foundErrors.map {
        info => s"Static reaction {$info} should not have a guard condition"
      }
    else Seq()
  }

  // Inspect the static molecules actually emitted. Their multiplicities must be not less than the declared multiplicities.
  private[jc] def findStaticMolsEmissionErrors(staticMolsDeclared: Map[MolEmitter, Int], staticMolsEmitted: Map[MolEmitter, Int]): Seq[String] = optimize {
    val foundErrors = staticMolsDeclared
      .filter {
        case (mol, count) => staticMolsEmitted.getOrElse(mol, 0) < count
      }
      .map {
        case (mol, count) =>
          val countEmitted = staticMolsEmitted.getOrElse(mol, 0)
          s"$mol emitted $countEmitted times instead of $count"
      }
    if (foundErrors.nonEmpty)
      Seq(s"Too few static molecules emitted: ${
        foundErrors.toSeq.sorted.mkString(", ")
      }")
    else Seq()
  }

  // Inspect the static molecules actually emitted. Their multiplicities must be not more than the declared multiplicities.
  private[jc] def findStaticMolsEmissionWarnings(staticMolsDeclared: Map[MolEmitter, Int], staticMolsEmitted: Map[MolEmitter, Int]) = optimize {
    val foundErrors = staticMolsDeclared
      .filter {
        case (mol, count) => staticMolsEmitted.getOrElse(mol, 0) > count
      }
      .map {
        case (mol, count) =>
          val countEmitted = staticMolsEmitted.getOrElse(mol, 0)
          s"$mol emitted $countEmitted times instead of $count"
      }
    if (foundErrors.nonEmpty)
      Seq(s"Possibly too many static molecules emitted: ${
        foundErrors.toSeq.sorted.mkString(", ")
      }")
    else Seq()
  }

}
