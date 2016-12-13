package code.winitzki.jc

import code.winitzki.jc.JoinRun._
import code.winitzki.jc.JoinRunUtils._

import scala.annotation.tailrec
import scala.collection.mutable


/** Represents the join definition, which holds one or more reaction definitions.
  * At run time, the join definition maintains a bag of currently available molecules
  * and runs reactions.
  * The user will never see any instances of this class.
  *
  * @param reactionInfos Complete information about reactions declared in this join definition.
  * @param reactionPool The thread pool on which reactions will be scheduled.
  * @param joinPool The thread pool on which the join definition will decide reactions and manage the molecule bag.
  */
private final case class JoinDefinition(
  reactionInfos: Map[Reaction, Seq[InputMoleculeInfo]],
  reactionPool: Pool,
  joinPool: Pool
) {

  /** Add a random shuffle method to sequences.
    *
    * @param a Sequence to be shuffled.
    * @tparam T Type of sequence elements.
    */
  private implicit final class ShufflableSeq[T](a: Seq[T]) {
    /** Shuffle sequence elements randomly.
      *
      * @return A new sequence with randomly permuted elements.
      */
    def shuffle: Seq[T] = scala.util.Random.shuffle(a)
  }

  // TODO: implement
  private val quiescenceCallbacks: mutable.Set[M[Unit]] = mutable.Set.empty

  private lazy val knownReactions: Seq[Reaction] = reactionInfos.keys.toSeq

  private lazy val stringForm = s"Join{${knownReactions.map(_.toString).sorted.mkString("; ")}}"

  override def toString = stringForm

  /** The sha1 hash sum of the entire join definition, computed from sha1 of each reaction.
    * The sha1 hash of each reaction is computed from the Scala syntax tree of the reaction's source code.
    * The result is implementation-dependent and is guaranteed to be the same for join definitions compiled from exactly the same source code with the same version of Scala compiler.
    */
  private lazy val sha1 = getSha1(knownReactions.map(_.info.sha1).sorted.mkString(","))

  private[jc] var logLevel = 0

  private[jc] def printBag: String = {
    val moleculesPrettyPrinted = if (moleculesPresent.size > 0) s"Molecules: ${moleculeBagToString(moleculesPresent)}" else "No molecules"

    s"${this.toString}\n$moleculesPrettyPrinted"
  }

  private[jc] def setQuiescenceCallback(callback: M[Unit]): Unit = {
    quiescenceCallbacks.add(callback)
  }

  private lazy val possibleReactions: Map[Molecule, Seq[Reaction]] = reactionInfos.toSeq
    .flatMap { case (r, ms) => ms.map { info => (info.molecule, r) } }
    .groupBy { case (m, r) => m }
    .map { case (m, rs) => (m, rs.map(_._2)) }

  // Initially, there are no molecules present.
  private val moleculesPresent: MoleculeBag = new MutableBag[Molecule, AbsMolValue[_]]

  private def moleculeBagToString(mb: MoleculeBag): String =
    mb.getMap.toSeq
      .map{ case (m, vs) => (m.toString, vs) }
      .sortBy(_._1)
      .flatMap {
        case (m, vs) => vs.map {
          case (mv, 1) => s"$m($mv)"
          case (mv, i) => s"$m($mv) * $i"
        }
      }.mkString(", ")

  private def moleculeBagToString(mb: LinearMoleculeBag): String =
    mb.map {
      case (m, jmv) => s"$m($jmv)"
    }.mkString(", ")

  private[jc] def injectMulti(moleculesAndValues: Seq[(M[_], Any)]): Unit = {
    // TODO: implement correct semantics
//    moleculesAndValues.foreach{ case (m, v) => m(v) }
  }

  // Adding a molecule may trigger at most one reaction.
  private[jc] def inject[T](m: Molecule, jmv: AbsMolValue[T]): Unit = {
    if (joinPool.isInactive) throw new ExceptionNoJoinPool(s"In $this: Cannot inject molecule $m since join pool is not active")
    else if (!Thread.currentThread().isInterrupted) joinPool.runClosure ({
      val (reaction, usedInputs: LinearMoleculeBag) = synchronized {
        moleculesPresent.addToBag(m, jmv)
        if (logLevel > 0) println(s"Debug: $this injecting $m($jmv) on thread pool $joinPool, now have molecules ${moleculeBagToString(moleculesPresent)}")
        val usedInputs: MutableLinearMoleculeBag = mutable.Map.empty
        val reaction = possibleReactions.get(m)
          .flatMap(_.shuffle.find(r => {
            usedInputs.clear()
            r.body.isDefinedAt(UnapplyRunCheck(moleculesPresent, usedInputs))
          }))
        reaction.foreach(_ => moleculesPresent.removeFromBag(usedInputs))
        (reaction, usedInputs.toMap)
      } // End of synchronized block.

      // We are just starting a reaction, so we don't need to hold the thread any more.
      reaction match {
        case Some(r) =>
          if (logLevel > 1) println(s"Debug: In $this: starting reaction {$r} on thread pool ${r.threadPool} while on thread pool $joinPool with inputs ${moleculeBagToString(usedInputs)}")
          if (logLevel > 2) println(
            if (moleculesPresent.size == 0)
              s"Debug: In $this: no molecules remaining"
            else
              s"Debug: In $this: remaining molecules ${moleculeBagToString(moleculesPresent)}"
          )
          // A basic check that we are using our mutable structures safely. We should never see this error.
          if (!r.inputMolecules.toSet.equals(usedInputs.keySet)) {
            val message = s"Internal error: In $this: attempt to start reaction {$r} with incorrect inputs ${moleculeBagToString(usedInputs)}"
            println(message)
            throw new ExceptionWrongInputs(message)
          }
          // Build a closure out of the reaction, and run that closure on the reaction's thread pool.
          val poolForReaction = r.threadPool.getOrElse(reactionPool)
          if (poolForReaction.isInactive) throw new ExceptionNoReactionPool(s"In $this: cannot run reaction $r since reaction pool is not active")
          else if (!Thread.currentThread().isInterrupted) poolForReaction.runClosure( {
            if (logLevel > 1) println(s"Debug: In $this: reaction {$r} started on thread pool $joinPool with thread id ${Thread.currentThread().getId}")
            try {
              // Here we actually apply the reaction body to its input molecules.
              r.body.apply(UnapplyRun(usedInputs))
            } catch {
              case e: ExceptionInJoinRun =>
                // Running the reaction body produced an exception that is internal to JoinRun.
                // We should not try to recover from this; it is most either an error on user's part
                // or a bug in JoinRun.
                println(s"In $this: Reaction {$r} produced an exception that is internal to JoinRun. Input molecules ${moleculeBagToString(usedInputs)} were not injected again. Exception trace will be printed now.")
                e.printStackTrace() // This will be printed asynchronously, out of order with the previous message.
                throw e

              case e: Exception =>
                // Running the reaction body produced an exception. Note that the exception has killed a thread.
                // We will now re-insert the input molecules. Hopefully, no side-effects or output molecules were produced so far.
                val aboutMolecules = if (r.retry) {
                  usedInputs.foreach { case (mol, v) => inject(mol, v) }
                  "were injected again"
                }
                else "were consumed and not injected again"

                println(s"In $this: Reaction {$r} produced an exception. Input molecules ${moleculeBagToString(usedInputs)} $aboutMolecules. Exception trace will be printed now.")
                e.printStackTrace() // This will be printed asynchronously, out of order with the previous message.
            }
            // For any blocking input molecules that have no reply, put an error message into them and reply with empty
            // value to unblock the threads.

            def nonemptyOpt[S](s: Seq[S]): Option[Seq[S]] = if (s.isEmpty) None else Some(s)

            // Compute error messages here in case we will need them later.
            val blockingMoleculesWithNoReply = nonemptyOpt(usedInputs
              .filter { case (_, BlockingMolValue(_, replyValue)) => replyValue.result.isEmpty; case _ => false }
              .keys.toSeq).map(_.map(_.toString).sorted.mkString(", "))

            val messageNoReply = blockingMoleculesWithNoReply map { s => s"Error: In $this: Reaction {$r} finished without replying to $s" }

            val blockingMoleculesWithMultipleReply = nonemptyOpt(usedInputs
              .filter { case (_, BlockingMolValue(_, replyValue)) => replyValue.repliedTwice; case _ => false }
              .keys.toSeq).map(_.map(_.toString).sorted.mkString(", "))

            val messageMultipleReply = blockingMoleculesWithMultipleReply map { s => s"Error: In $this: Reaction {$r} replied to $s more than once" }

            // We will report all errors to each blocking molecule.
            val errorMessage = Seq(messageNoReply, messageMultipleReply).flatten.mkString("; ")
            val haveError = blockingMoleculesWithNoReply.nonEmpty || blockingMoleculesWithMultipleReply.nonEmpty

            // Insert error messages into the reply wrappers and release all semaphores.
            usedInputs.foreach {
              case (_, BlockingMolValue(_, replyValue)) =>
                if (haveError) {
                  replyValue.errorMessage = Some(errorMessage)
                }
                replyValue.releaseSemaphore()

              case _ => ()
            }

            if (haveError) {
              println(errorMessage)
              throw new Exception(errorMessage)
            }
          }, name = Some(s"[Reaction $r in $this]"))

        case None =>
          if (logLevel > 2) println(s"Debug: In $this: no reactions started")
          ()

      }

    }, name = Some(s"[Injecting $m($jmv) in $this]"))
  }


  // Adding a blocking molecule may trigger at most one reaction and must return a value of type R.
  // We must make this a blocking call, so we acquire a semaphore (with timeout).
  private[jc] def injectAndReply[T,R](m: B[T,R], v: T, valueWithResult: ReplyValue[R]): R = {
    inject(m, BlockingMolValue(v, valueWithResult))
    try {
      // not sure we need this.
      BlockingIdle {
        valueWithResult.acquireSemaphore()
      }
    }
    catch {
      case e: InterruptedException => e.printStackTrace()
    }
    finally {
      valueWithResult.deleteSemaphore() // make sure it's gone
    }
    // check if we had any errors, and that we have a result value
    valueWithResult.errorMessage match {
      case Some(message) => throw new Exception(message)
      case None => valueWithResult.result.getOrElse(
        throw new ExceptionEmptyReply(s"Internal error: In $this: $m received an empty reply without an error message"
        )
      )
    }
  }

  private[jc] def injectAndReplyWithTimeout[T,R](timeout: Long, m: B[T,R], v: T, valueWithResult: ReplyValue[R]):
  Option[R] = {
    inject(m, BlockingMolValue(v, valueWithResult))
    val success =
      try {
        // not sure we need this.
        BlockingIdle {
          valueWithResult.acquireSemaphore(Some(timeout))
        }
      }
      catch {
        case e: InterruptedException => e.printStackTrace(); false
        case _: Exception => false
      }
      finally {
        valueWithResult.deleteSemaphore() // make sure it's gone
      }
    // check if we had any errors, and that we have a result value
    valueWithResult.errorMessage match {
      case Some(message) => throw new Exception(message)
      case None => if (success) Some(valueWithResult.result.getOrElse(
        throw new ExceptionEmptyReply(s"Internal error: In $this: $m received an empty reply without an error message"))
      )
      else None

    }
  }

  def getVolatileReader[T](m: M[T]): VolatileReader[T] = {
    // TODO: implement correct functionality for singletons

    throw new ExceptionNoSingleton(s"In $this: volatile reader requested for $m, which is not a singleton")
  }

}

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
      Some(s"Unavoidable livelock: reaction${if (warningList.size == 1) "" else "s"} ${warningList.mkString(", ")}")
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
      possibleReactions = Set(bInput, mInput).flatMap(_.molecule.injectingReactions).flatten.toSeq
      reaction <- possibleReactions
      outputs <- reaction.info.outputs
      if outputs.nonEmpty
      if outputs.map(_.molecule).slice(outputs.map(_.molecule).indexOf(bInput.molecule), outputs.size).contains(mInput.molecule)
    } yield {
      // Find a reaction that first injects bInput and then injects mInput, with stronger matchers than bInput and mInput respectively.
      (bInput, mInput, reaction)
    }

    val warningList = likelyDeadlocks
      .map { case (bInput, mInput, reaction) => s"molecule ${bInput.molecule} may deadlock due to ${mInput.molecule} among the outputs of ${reaction.info}"}
    if (warningList.nonEmpty)
      Some(s"Likely deadlock${if (warningList.size == 1) "" else "s"}: ${warningList.mkString("; ")}")
    else None
  }

  private def checkOutputsForDeadlockWarning(reactions: Seq[Reaction]): Option[String] = {
    // A "possible deadlock" means that an output blocking molecule is followed by other output molecules.
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
      Some(s"Likely deadlock${if (warningList.size == 1) "" else "s"}: ${warningList.mkString("; ")}")
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