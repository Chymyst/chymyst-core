package code.winitzki.jc

import code.winitzki.jc.JoinRun._

import scala.annotation.tailrec


private object StaticChecking {

  private val patternIsNotUnknown: InputMoleculeInfo => Boolean =
    i => i.flag != UnknownInputPattern

  /** Check that every input molecule matcher of one reaction is weaker than a corresponding matcher in another reaction.
    * If true, it means that the first reaction can start whenever the second reaction can start, which is an instance of unavoidable indeterminism.
    * The input1, input2 list2 should not contain UnknownInputPattern.
    *
    * @param input1 Sorted input list for the first reaction.
    * @param input2 Sorted input list  for the second reaction.
    * @return True if the first reaction is weaker than the second.
    */
  @tailrec
  private def allMatchersAreWeakerThan(input1: List[InputMoleculeInfo], input2: List[InputMoleculeInfo]): Boolean =
  input1.forall(patternIsNotUnknown) && {
    val input2filtered = input2.filter(patternIsNotUnknown)
    input1 match {
      case Nil => true // input1 has no matchers left
      case info1 :: rest1 => input2filtered match {
        case Nil => false // input1 has matchers but input2 has no matchers left
        case _ =>
          val isWeaker: InputMoleculeInfo => Boolean =
            i => info1.matcherIsWeakerThan(i).getOrElse(false)

          input2filtered.find(isWeaker) match {
            case Some(correspondingMatcher) => allMatchersAreWeakerThan(rest1, input2filtered diff List(correspondingMatcher))
            case None => false
          }
      }
    }
  }

  @tailrec
  private def inputMatchersAreWeakerThanOutput(input: List[InputMoleculeInfo], output: List[OutputMoleculeInfo]): Boolean = {
    input match {
      case Nil => true
      case info :: rest => output match {
        case Nil => false
        case _ =>
          val isWeaker: OutputMoleculeInfo => Boolean =
            i => info.matcherIsWeakerThanOutput(i).getOrElse(false)

          output.find(isWeaker) match {
            case Some(correspondingMatcher) => inputMatchersAreWeakerThanOutput(rest, output diff List(correspondingMatcher))
            case None => false
          }
      }
    }
  }

  @tailrec
  private def inputMatchersAreSimilarToOutput(input: List[InputMoleculeInfo], output: List[OutputMoleculeInfo]): Boolean = {
    input match {
      case Nil => true
      case info :: rest => output match {
        case Nil => false
        case _ =>
          val isWeaker: OutputMoleculeInfo => Boolean =
            i => info.matcherIsSimilarToOutput(i).getOrElse(false)

          output.find(isWeaker) match {
            case Some(correspondingMatcher) => inputMatchersAreSimilarToOutput(rest, output diff List(correspondingMatcher))
            case None => false
          }
      }
    }
  }

  private def inputMatchersWeakerThanOutput(input: List[InputMoleculeInfo], outputsOpt: Option[List[OutputMoleculeInfo]]) =
    outputsOpt.exists {
      outputs => input.forall(patternIsNotUnknown) && inputMatchersAreWeakerThanOutput(input, outputs)
    }

  private def inputMatchersSimilarToOutput(input: List[InputMoleculeInfo], outputsOpt: Option[List[OutputMoleculeInfo]]) =
    outputsOpt.exists {
      outputs => inputMatchersAreSimilarToOutput(input, outputs)
    }

  // Reactions whose inputs are all unconditional matchers and are a subset of inputs of another reaction:
  private def checkReactionShadowing(reactions: Seq[Reaction]): Option[String] = {
    val suspiciousReactions = for {
      r1 <- reactions
      r2 <- reactions
      if r1 != r2
      if r1.info.hasGuard.knownFalse
      if allMatchersAreWeakerThan(r1.info.inputsSorted, r2.info.inputsSorted)
    } yield {
      (r1, r2)
    }

    if (suspiciousReactions.nonEmpty) {
      val errorList = suspiciousReactions.map{ case (r1, r2) =>
        s"reaction $r2 is shadowed by $r1"
      }.mkString(", ")
      Some(s"Unavoidable indeterminism: $errorList")
    } else None
  }

  private def checkSingleReactionLivelock(reactions: Seq[Reaction]): Option[String] = {
    val errorList = reactions
      .filter { r => r.info.hasGuard.knownFalse && inputMatchersWeakerThanOutput(r.info.inputsSorted, r.info.outputs)}
      .map(_.toString)
    if (errorList.nonEmpty)
      Some(s"Unavoidable livelock: reaction${if (errorList.size == 1) "" else "s"} ${errorList.mkString(", ")}")
    else None
  }

  private def checkMultiReactionLivelock(reactions: Seq[Reaction]): Option[String] = {
    // TODO: implement
    None
  }

  private def checkSingleReactionLivelockWarning(reactions: Seq[Reaction]): Option[String] = {
    val warningList = reactions
      .filter { r => inputMatchersSimilarToOutput(r.info.inputsSorted, r.info.outputs)}
      .map(_.toString)
    if (warningList.nonEmpty)
      Some(s"Possible livelock: reaction${if (warningList.size == 1) "" else "s"} ${warningList.mkString(", ")}")
    else None
  }

  private def checkInputsForDeadlockWarning(reactions: Seq[Reaction]): Option[String] = {
    // A "possible deadlock" means that an input blocking molecule is consumed together with other molecules that are injected later by the same reactions that inject the blocking molecule.
    val blockingInputsWithNonblockingInputs: Seq[(InputMoleculeInfo, List[InputMoleculeInfo])] =
      reactions.map(_.info.inputsSorted.partition(_.molecule.isBlocking)).filter(m => m._1.nonEmpty && m._2.nonEmpty)
        .flatMap { case (bInputs, mInputs) => bInputs.map(b => (b, mInputs)) }

    val likelyDeadlocks: Seq[(InputMoleculeInfo, InputMoleculeInfo, Reaction)] = for {
      bmInputs <- blockingInputsWithNonblockingInputs
      (bInput, mInputInfos) = bmInputs
      mInput <- mInputInfos
      possibleReactions = Set(bInput, mInput).flatMap(_.molecule.injectingReactions).toSeq
      reaction <- possibleReactions
      outputs <- reaction.info.outputs
      outputMolecules = outputs.map(_.molecule)
      if outputMolecules.nonEmpty
      // Find a reaction that first injects bInput and then injects mInput, with stronger matchers than bInput and mInput respectively.
      if outputMolecules.contains(bInput.molecule)
      bInputIndex = outputMolecules.indexOf(bInput.molecule)
      mInputIndex = outputMolecules.indexOf(mInput.molecule)
      if mInputIndex > bInputIndex
      if bInput.matcherIsSimilarToOutput(outputs(bInputIndex)).getOrElse(false)
      if mInput.matcherIsSimilarToOutput(outputs(mInputIndex)).getOrElse(false)
    } yield {
      (bInput, mInput, reaction)
    }

    val warningList = likelyDeadlocks
      .map { case (bInput, mInput, reaction) => s"molecule (${bInput.molecule}) may deadlock due to (${mInput.molecule}) among the outputs of ${reaction.info}"}
    if (warningList.nonEmpty)
      Some(s"Possible deadlock${if (warningList.size == 1) "" else "s"}: ${warningList.mkString("; ")}")
    else None
  }

  private def checkOutputsForDeadlockWarning(reactions: Seq[Reaction]): Option[String] = {
    // A "possible deadlock" means that an output blocking molecule is followed by other output molecules that the blocking molecule may be waiting for.
    val possibleDeadlocks: Seq[(OutputMoleculeInfo, List[OutputMoleculeInfo])] =
      reactions.flatMap(_.info.outputs)
        .flatMap {
          _.tails.filter {
            case t :: ts => t.molecule.isBlocking
            case Nil => false
          }.map { l => (l.head, l.tail) } // The filter above guarantees that `l` is non-empty now.
        }
    // The chemistry is likely to be a deadlock if at least one the other output molecules are consumed together with the blocking molecule in the same reaction.
    val likelyDeadlocks = possibleDeadlocks.map {
      case (info, infos) =>
        (info, info.molecule.consumingReactions.flatMap(
          _.find { r =>
            // For each reaction that consumes the molecule `info.molecule`, check whether this reaction also consumes any of the molecules from infos.map(_.molecule). If so, it's a likely deadlock.
            val uniqueInputsThatAreAmongOutputs = r.info.inputsSorted
              .filter(infos.map(_.molecule) contains _.molecule)
              .groupBy(_.molecule).mapValues(_.last).values.toList // Among repeated input molecules, choose only one molecule with the weakest matcher.

            uniqueInputsThatAreAmongOutputs.exists(infoInput =>
              infos.exists(infoOutput =>
                infoInput.matcherIsWeakerThanOutput(infoOutput).getOrElse(false))
            )
          }
        )
        )
    }

    val warningList = likelyDeadlocks
      .filter{ _._2.nonEmpty }
      .map { case (info, reactionOpt) => s"molecule ${info.molecule} may deadlock due to outputs of ${reactionOpt.get.info}"}
    if (warningList.nonEmpty)
      Some(s"Possible deadlock${if (warningList.size == 1) "" else "s"}: ${warningList.mkString("; ")}")
    else None
  }

  private[jc] def findStaticErrors(reactions: Seq[Reaction]) = {
    Seq(
      checkReactionShadowing _,
      checkSingleReactionLivelock _,
      checkMultiReactionLivelock _
    ).flatMap(_(reactions))
  }

  private[jc] def findStaticWarnings(reactions: Seq[Reaction]) = {
    Seq(
      checkOutputsForDeadlockWarning _,
      checkInputsForDeadlockWarning _,
      checkSingleReactionLivelockWarning _
    ).flatMap(_(reactions))
  }


}
