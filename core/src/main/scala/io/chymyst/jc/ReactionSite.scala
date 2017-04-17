package io.chymyst.jc

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicIntegerArray

import Core._
import StaticAnalysis._

import scala.annotation.tailrec
import scala.collection.{breakOut, mutable}

/** Represents the reaction site, which holds one or more reaction definitions (chemical laws).
  * At run time, the reaction site maintains a bag of currently available input molecules and runs reactions.
  * The user will never see any instances of this class.
  *
  * @param reactions    List of reactions as defined by the user.
  * @param reactionPool The thread pool on which reactions will be scheduled.
  * @param sitePool     The thread pool on which the reaction site will decide reactions and manage the molecule bag.
  */
private[jc] final class ReactionSite(reactions: Seq[Reaction], reactionPool: Pool, sitePool: Pool) {

  /** Create a wrapper class instance, to be given to each molecule bound to this reaction site.
    *
    * @param molecule The calling molecule.
    * @tparam T The type of value carried by that molecule.
    * @tparam R The type of reply value for that molecule. If the molecule is non-blocking, `R` is set to `Unit`.
    * @return A new instance of [[ReactionSiteWrapper]] given to that molecule.
    */
  private[jc] def makeWrapper[T, R](molecule: Molecule): ReactionSiteWrapper[T, R] = new ReactionSiteWrapper[T, R](
    toString,
    logSoup = () => printBag,
    setLogLevel = { level => logLevel = level },
    isStatic = () ⇒ staticMolDeclared.contains(molecule),
    emit = (mol, molValue) => emit[T](mol, molValue),
    emitAndAwaitReply = (mol, molValue, replyValue) => emitAndAwaitReply[T, R](mol, molValue, replyValue),
    emitAndAwaitReplyWithTimeout = (timeout, mol, molValue, replyValue) => emitAndAwaitReplyWithTimeout[T, R](timeout, mol, molValue, replyValue),
    consumingReactions = consumingReactions(molecule.siteIndex),
    sameReactionSite = _.id === this.id
  )

  private def getConsumingReactions(m: Molecule): Array[Reaction] =
    reactionInfos.keys.filter(_.inputMoleculesSet contains m).toArray

  /** Each reaction site has a permanent unique ID number.
    *
    */
  private val id: Long = getNextId

  private val (staticReactions, nonStaticReactions) = reactions.toArray.partition(_.info.isStatic)

  /** The table of statically declared static molecules and their multiplicities.
    * Only non-blocking molecules can be static.
    * Static molecules are emitted by "static reactions" (i.e. { case _ => ...}), which are run only once at the reaction site activation time.
    * This list may be incorrect if the static reaction code emits molecules conditionally or emits many copies.
    * So, the code (1 to 10).foreach (_ => s() ) will put (s -> 1) into `staticMolDeclared` but (s -> 10) into `staticMolsEmitted`.
    */
  private val staticMolDeclared: Map[Molecule, Int] = staticReactions.map(_.info.outputs)
    .flatMap(_.map(_.molecule).filterNot(_.isBlocking))
    .groupBy(identity)
    .mapValues(_.length)

  /** Complete information about reactions declared in this reaction site.
    * Static reactions are not included here.
    */
  private val reactionInfos: Map[Reaction, Array[InputMoleculeInfo]] = nonStaticReactions
    .map { r => (r, r.info.inputs) }(breakOut)

  private val toStringLimit = 1024

  override val toString: String = {
    val raw = s"Site{${nonStaticReactions.map(_.toString).sorted.mkString("; ")}}".take(toStringLimit + 1)
    if (raw.length > toStringLimit)
      raw.substring(0, toStringLimit) + "..."
    else raw
  }

  /** The sha1 hash sum of the entire reaction site, computed from sha1 of each reaction.
    * The sha1 hash of each reaction is computed from the Scala syntax tree of the reaction's source code.
    * The result is implementation-dependent and is guaranteed to be the same only for reaction sites compiled from exactly the same source code with the same version of Scala compiler.
    */
  //  private lazy val sha1 = getSha1String(knownReactions.map(_.info.sha1).sorted.mkString(","))

  private var logLevel = -1

  private def printBag: String = {
    val moleculesPrettyPrinted = if (moleculesPresent.exists(!_.isEmpty))
      s"Molecules: ${moleculeBagToString(moleculesPresent)}"
    else "No molecules"

    s"$toString\n$moleculesPrettyPrinted"
  }

  private def addLockInfoToUnions(lock: MoleculeValueLock): Unit = {
    moleculeLockUnion ++= lock.molecules
    lock.counts.foreach { case (siteIndex, count) ⇒
      countLockUnion.update(siteIndex, countLockUnion.getOrElse(siteIndex, 0) + count)
    }
  }

  private def removeLockInfoFromUnions(lock: MoleculeValueLock): Unit = {
    moleculeLockUnion --= lock.molecules
    lock.counts.foreach { case (siteIndex, count) ⇒
      val newCount = countLockUnion.getOrElse(siteIndex, 0) - count
      if (newCount <= 0)
        countLockUnion.remove(siteIndex)
      else
        countLockUnion.update(siteIndex, newCount)
    }
  }

  private val countLockUnion = mutable.Map[Int, Int]()

  private val moleculeLockUnion = mutable.Set[Int]()

  private val waitingLocks = mutable.Set[MoleculeValueLock]()

  /** Determine whether the lock can be granted. This function is called only in the synchronized context.
    *
    * @param lock A requested lock.
    * @return A pair of Boolean values. The first value is `true` if we have in principle enough molecules for the requested
    *         lock. The second value is `true` if the requested lock can be actually granted now.
    */
  private def tryAcquireLock(lock: MoleculeValueLock): (Boolean, Boolean) = {
    val sizes: Map[Int, Int] = lock.usedMoleculesSeq.map(i ⇒ i → moleculesPresent(i).size)(breakOut)
    if (lock.usedMoleculesSeq.exists(i ⇒ sizes(i) == 0)) // We fail if we require some molecule that is not present at all.
      (false, false)
    else if (lock.molecules.exists { i ⇒ moleculeLockUnion.contains(i) || countLockUnion.contains(i) } ||
      lock.counts.exists {
        case (i, count) ⇒
          moleculeLockUnion.contains(i) ||
            countLockUnion.getOrElse(i, 0) + count > sizes(i)
      }
    )
      (true, false)
    else (true, true)
  }

  private def acquireLock(lock: MoleculeValueLock): Boolean = moleculesPresent.synchronized {
    /*
     If the present molecule counts are zero for any molecule in the requested lock, return `false`.
     Otherwise:
     If the present molecule counts minus the counts in the countLockUnion are >= the requested count map,
     and if the moleculeLockUnion has empty intersection with the requested lock's `usedMolecules`,
       add lock info to the unions
       return `true`
     Otherwise:
      add the requested lock to the waiting locks set
      acquire semaphore (block with no timeout)
      return semaphore's acquireStatus
    */
    val (haveMolecules, haveLock) = tryAcquireLock(lock)
    if (haveMolecules) {
      if (haveLock) {
        addLockInfoToUnions(lock)
        true
      } else {
        waitingLocks.add(lock)
        lock.acquireSemaphore()
        lock.acquireStatus
      }
    } else false
  }

  private def releaseLock(oldLock: MoleculeValueLock): Unit = moleculesPresent.synchronized {
    /*
    Remove oldLock info from the unions.
    For each waiting lock that has an intersection with this oldLock:
      Check whether that lock can be granted.
      If so, add lock info to the unions, remove lock from waiting locks set, and grant the semaphore for that lock.
     */
    removeLockInfoFromUnions(oldLock)
    val candidateLocks = waitingLocks.filter(_.usedMoleculesSet.exists(oldLock.usedMoleculesSet.contains))
    candidateLocks.foreach { lock ⇒
      val (haveMolecules, haveLock) = tryAcquireLock(lock)
      if (haveMolecules) {
        if (haveLock) {
          waitingLocks.remove(lock)
          removeLockInfoFromUnions(lock)
          lock.grantSemaphore(true)
        }
      } else lock.grantSemaphore(false)
    }
  }

  private lazy val needScheduling: AtomicIntegerArray = new AtomicIntegerArray(knownMolecules.size)

  private val NEED_TO_SCHEDULE = 0

  private val NO_NEED_TO_SCHEDULE = 1

  private def noNeedToSchedule(mol: Molecule): Unit = needScheduling.set(mol.siteIndex, NO_NEED_TO_SCHEDULE)

  private def needToSchedule(mol: Molecule): Unit = needScheduling.set(mol.siteIndex, NEED_TO_SCHEDULE)

  private def schedulingNeeded(mol: Molecule): Boolean = needScheduling.compareAndSet(mol.siteIndex, NEED_TO_SCHEDULE, NO_NEED_TO_SCHEDULE)

  @tailrec
  private def decideReactionsForNewMolecule(mol: Molecule): Unit = {
    // TODO: optimize: precompute all related molecules in ReactionSite?
    lazy val decidingMoleculeMessage = s"Debug: In $this: deciding reactions for molecule $mol, present molecules [${moleculeBagToString(moleculesPresent)}]"
    if (logLevel > 3) println(decidingMoleculeMessage)
    needToSchedule(mol)
    val foundReactionAndInputs = {
      // This option value will be non-empty if we have a reaction with some input molecules that all have admissible values for that reaction.
      val found: Option[(Reaction, InputMoleculeList)] =
        findReaction(consumingReactions(mol.siteIndex))
      // If we have found a reaction that can be run, remove its input molecule values from their bags.
      found.foreach { case (thisReaction, thisInputList) ⇒
        thisInputList.indices.foreach { i ⇒
          val molValue = thisInputList(i)
          val mol = thisReaction.info.inputs(i).molecule
          // This error (molecule value was not in bag) indicates a bug in this code, which should already manifest itself in failing tests! We can't cover this error by tests if the code is correct.
          if (!removeFromBag(mol, molValue)) reportError(s"Error: In $this: Internal error: Failed to remove molecule $mol($molValue) from its bag; molecule index ${mol.siteIndex}, bag ${moleculesPresent(mol.siteIndex)}")
        }
        noNeedToSchedule(mol)
      }
      found
    }
    // End of synchronized block.
    // We already decided on starting a reaction, so we don't hold the `synchronized` lock on the molecule bag any more.

    foundReactionAndInputs match {
      case Some((thisReaction, usedInputs)) =>
        // Build a closure out of the reaction, and run that closure on the reaction's thread pool.
        val poolForReaction = thisReaction.threadPool.getOrElse(reactionPool)
        if (poolForReaction.isInactive) {
          reportError(s"In $this: cannot run reaction {${thisReaction.info}} since reaction pool is not active; input molecules ${Core.moleculeBagToString(thisReaction, usedInputs)} were consumed and not emitted again")
          // In this case, we do not attempt to schedule a reaction. However, input molecules were consumed and not emitted again.
        } else {
          if (!Thread.currentThread().isInterrupted) {
            if (logLevel > 1) println(s"Debug: In $this: starting reaction {${thisReaction.info}} with inputs [${Core.moleculeBagToString(thisReaction, usedInputs)}] on reaction pool $poolForReaction while on site pool $sitePool")
          }
          if (logLevel > 2) {
            val moleculesRemainingMessage =
              if (moleculesPresent.forall(_.isEmpty))
                noMoleculesRemainingMessage
              else
                s"Debug: In $this: remaining molecules [${moleculeBagToString(moleculesPresent)}]"
            println(moleculesRemainingMessage)
          }
          // Schedule the reaction now. Provide reaction info to the thread.
          scheduleReaction(thisReaction, usedInputs, poolForReaction)
          // The scheduler loops, trying to run another reaction with the same molecule, if possible. This is required for correct operation.
          decideReactionsForNewMolecule(mol)
        }
      case None =>
        if (logLevel > 2)
          println(noReactionsStartedMessage)
    }
  }

  private val noMoleculesRemainingMessage = s"Debug: In $this: no molecules remaining"

  private val noReactionsStartedMessage = s"Debug: In $this: no reactions started"

  private def scheduleReaction(reaction: Reaction, usedInputs: InputMoleculeList, poolForReaction: Pool): Unit =
    poolForReaction.runClosure(runReaction(reaction, usedInputs, poolForReaction: Pool), reaction.newChymystThreadInfo)

  private def emissionRunnable(mol: Molecule): Runnable = new Runnable {
    override def run(): Unit = {
      val reactions = consumingReactions(mol.siteIndex)
      reactions.synchronized {
        // The mutating shuffle is thread-safe because it is inside a `synchronized` block.
        arrayShuffleInPlace(reactions)
      }
      decideReactionsForNewMolecule(mol)
    }
  }

  private def reportError(message: String): Unit = {
    if (logLevel >= 0) println(message)
    Core.reportError(message)
  }

  private sealed trait ReactionExitStatus {
    def getMessage: Option[String] = None

    def reactionSucceededOrFailedWithoutRetry: Boolean = true
  }

  private case object ReactionExitSuccess extends ReactionExitStatus

  private final case class ReactionExitFailure(message: String) extends ReactionExitStatus {
    override def getMessage: Option[String] = Some(message)
  }

  private final case class ReactionExitRetryFailure(message: String) extends ReactionExitStatus {
    override def getMessage: Option[String] = Some(message)

    override def reactionSucceededOrFailedWithoutRetry: Boolean = false
  }

  /** This closure will be run on the reaction thread pool to start a new reaction.
    *
    * @param thisReaction Reaction to run.
    * @param usedInputs   Molecules (with values) that are consumed by the reaction.
    */
  private def runReaction(thisReaction: Reaction, usedInputs: InputMoleculeList, poolForReaction: Pool): Unit = {
    lazy val reactionStartMessage = s"Debug: In $this: reaction {${thisReaction.info}} started on thread pool $reactionPool with thread id ${Thread.currentThread().getId}"

    if (logLevel > 1) println(reactionStartMessage)
    lazy val reactionInputs = Core.moleculeBagToString(thisReaction, usedInputs)
    val exitStatus: ReactionExitStatus = try {
      // Here we actually apply the reaction body to its input molecules.
      thisReaction.body.apply((usedInputs.length - 1, usedInputs))
      // If we are here, we had no exceptions during evaluation of reaction body.
      ReactionExitSuccess
    } catch {
      // Catch various exceptions that occurred while running the reaction body.
      case e: ExceptionInChymyst =>
        // Running the reaction body produced an exception that is internal to `Chymyst Core`.
        // We should not try to recover from this; it is either an error on user's part or a bug in `Chymyst Core`.
        val message = s"In $this: Reaction {${thisReaction.info}} with inputs [$reactionInputs] produced an exception internal to Chymyst Core. Retry run was not scheduled. Message: ${e.getMessage}"
        reportError(message)
        ReactionExitFailure(message)

      case e: Exception =>
        // Running the reaction body produced an exception. Note that the exception has killed a thread.
        // We will now schedule this reaction again if retry was requested. Hopefully, no side-effects or output molecules were produced so far.
        val (status, retryMessage) =
          if (thisReaction.retry) {
            scheduleReaction(thisReaction, usedInputs, poolForReaction)
            (ReactionExitRetryFailure(e.getMessage), " Retry run was scheduled.")
          }
          else (ReactionExitFailure(e.getMessage), " Retry run was not scheduled.")

        val generalExceptionMessage = s"In $this: Reaction {${thisReaction.info}} with inputs [$reactionInputs] produced ${e.getClass.getSimpleName}.$retryMessage Message: ${e.getMessage}"

        reportError(generalExceptionMessage)
        status
    }

    // Make this non-lazy to improve coverage. If the code is correct, the no-reply error cannot happen with `ReactionExitSuccess`.
    // The missing coverage is the evaluation of `.getMessage` on the status value `ReactionExitSuccess`.
    val errorMessageFromStatus = exitStatus.getMessage.map(message ⇒ s". Reported error: $message").getOrElse("")

    // The reaction is finished. If it had any blocking input molecules, we check if any of them got no reply.
    if (thisReaction.info.hasBlockingInputs && usedInputs.exists(_.reactionSentNoReply)) {
      // For any blocking input molecules that have no reply, put an error message into them and reply with empty value to unblock the threads.

      // Compute error messages here in case we will need them later.
      val blockingMoleculesWithNoReply = usedInputs.zipWithIndex
        .filter(_._1.reactionSentNoReply)
        .map { case (_, i) ⇒ thisReaction.info.inputs(i).molecule }
        .toSeq.toOptionSeq
        .map(_.map(_.toString).sorted.mkString(", "))

      lazy val messageNoReply = blockingMoleculesWithNoReply.map { s =>
        s"Error: In $this: Reaction {${thisReaction.info}} with inputs [$reactionInputs] finished without replying to $s$errorMessageFromStatus"
      }

      // We will report all errors to each blocking molecule.
      // However, if the reaction failed with retry, we don't yet need to release semaphores and don't need to report errors due to missing reply.
      lazy val errorMessage = messageNoReply.mkString("; ")

      val haveErrorsWithBlockingMolecules = blockingMoleculesWithNoReply.nonEmpty && exitStatus.reactionSucceededOrFailedWithoutRetry

      // Insert error messages into the reply wrappers and release all semaphores.
      usedInputs.foreach {
        case bm@BlockingMolValue(_, replyValue) =>
          if (haveErrorsWithBlockingMolecules && exitStatus.reactionSucceededOrFailedWithoutRetry) {
            // Do not send error messages to molecules that already got a reply - this is pointless and leads to errors.
            if (bm.reactionSentNoReply) {
              replyValue.acquireSemaphoreForReply()
              replyValue.setErrorStatus(errorMessage)
              replyValue.releaseSemaphoreForEmitter()
            }
          }
        case _ => ()
      }

      if (haveErrorsWithBlockingMolecules) reportError(errorMessage)
    }
  }

  /** Determine whether the current thread is running a reaction, and if so, fetch the reaction info.
    *
    * @return `None` if the current thread is not running a reaction.
    */
  private def currentReactionInfo: Option[ChymystThreadInfo] = {
    Thread.currentThread match {
      case t: ThreadWithInfo => t.chymystInfo
      case _ => None
    }
  }

  /** We only need to find one reaction whose input molecules are available.
    * For this, we use the special method [[ArrayWithExtraFoldOps.findAfterMap]].
    * We return `Option[(Reaction, InputMoleculeList)]` indicating both the selected reaction and its input molecule values.
    *
    * @param reactions Array of reactions that need to be checked for possibly starting them.
    * @return `None` if no reaction can be started. Otherwise, the tuple contains the selected reaction
    *         and its input molecule values.
    */
  private def findReaction(reactions: Array[Reaction]): Option[(Reaction, InputMoleculeList)] =
    reactions
      .filter(_.info.guardPresence.staticGuardHolds())
      .findAfterMap { r ⇒
        if (acquireLock(r.initialMoleculeValueLock)) {
          val result = findInputMolecules(r, moleculesPresent)
          releaseLock(r.initialMoleculeValueLock)
          result
        } else
          None
      }

  /** Find a set of input molecule values for a reaction. */
  private def findInputMolecules(r: Reaction, moleculesPresent: MoleculeBagArray): Option[(Reaction, InputMoleculeList)] = {
    val info = r.info
    // This array will be mutated in place as we search for molecule values.
    val foundValues = new Array[AbsMolValue[_]](info.inputs.length)

    val foundResult: Boolean =
    // `foundResult` will be `true` (and then `foundValues` has the molecule values) or `false` (we found no values that match).

    // Handle molecules that have no cross-dependencies of molecule values, but have conditionals.
    // For each single (non-repeated) input molecule, select a molecule value that satisfies the conditional.

    // If we fail to find all such values, `foundResult` will be `false`.
      info.inputsSortedIndependentConditional.forall { inputInfo ⇒
        val molBag = moleculesPresent(inputInfo.molecule.siteIndex)
        val newValueOpt =
        // It is probably useless to try optimizing the selection of a constant value, because 1) values are wrapped and 2) values that are not "simple types" are most likely to be stored in a queue-based molecule bag rather than in a hash map-based molecule bag.
        // So we handle pipelined and non-pipelined molecules here, without a special case for constant values.
          if (inputInfo.molecule.isPipelined)
            molBag.takeOne.filter(inputInfo.admitsValue) // For pipelined molecules, we take the first one; if condition fails, we treat that case as if no molecule is available.
          else
            molBag.find(inputInfo.admitsValue)

        newValueOpt.foreach { newMolValue ⇒
          foundValues(inputInfo.index) = newMolValue
        }
        newValueOpt.nonEmpty
      } && {
        // Here we handle independent irrefutable molecules.
        // It is important to assign these molecule values here before we embark on the SearchDSL program for cross-molecule groups
        // because the SearchDSL program does not include independent molecules, so they have to be assigned now.

        // This value will be `true` if we could get sufficient counts for all required molecules from `inputsSortedIndependentIrrefutableGrouped`.
        info.inputsSortedIndependentIrrefutableGrouped
          .forall { case (molSiteIndex, molInputIndices) ⇒
            val molValuesFound = moleculesPresent(molSiteIndex).takeAny(r.moleculeIndexRequiredCounts(molSiteIndex))
            // This will give `false` if we failed to find a sufficient number of molecule values.
            (molValuesFound.length === molInputIndices.length) && {
              molInputIndices.indices.foreach(i ⇒ foundValues(molInputIndices(i)) = molValuesFound(i))
              true
            }
          }
      } && {
        // If we have no cross-conditionals, we do not need to use the SearchDSL sequence and we are finished.
        if (info.crossGuards.isEmpty && info.crossConditionalsForRepeatedMols.isEmpty)
          true
        else {
          // Map from site-wide molecule index to the multiset of values that have been selected for repeated copies of this molecule.
          // This is used only for selecting repeated input molecules.
          type MolVals = Map[Int, List[AbsMolValue[_]]]

          // We are using a much faster Iterator instead of Stream now. Conceptually it's a stream of `MolVals` values.
          val initStream = Iterator[MolVals](Map())

          val found: Option[Iterator[MolVals]] = r.info.searchDSLProgram
            // The `flatFoldLeft` accumulates the value `repeatedMolValues`, representing the stream of value maps for repeated input molecules (only).
            // This is used to build a "skipping iterator" over molecule values that correctly handles repeated input molecules.

            // This is a "flat fold" because should be able to stop early even though we can't examine the stream value.
            .flatFoldLeft[Iterator[MolVals]](initStream) { (repeatedMolValuesStream, searchDslCommand) ⇒
            // We need to return Option[Iterator[MolVals]].
            searchDslCommand match {
              case ChooseMol(i) ⇒
                // Note that this molecule cannot be pipelined since it is part of a cross-molecule constraint.
                val inputInfo = info.inputs(i)

                Some(// The stream contains repetitions of the immutable values `repeatedVals` of type `MolVals`, which represents the value map for repeated input molecules.
                  // If there are no repeated input molecules, this will be an empty map.
                  // However, each item in the stream will mutate `foundValues` in place, so that we always have the last chosen molecule values.
                  // The search DSL program is guaranteed to check cross-molecule conditions only for molecules whose values we already chose.
                  repeatedMolValuesStream.flatMap { repeatedVals ⇒
                    val siteMolIndex = inputInfo.molecule.siteIndex
                    if (info.crossConditionalsForRepeatedMols contains i) {
                      val prevValMap = repeatedVals.getOrElse(siteMolIndex, List[AbsMolValue[_]]())
                      moleculesPresent(siteMolIndex)
                        // TODO: move this to the skipping interface, restore Seq[T] as its argument
                        .allValuesSkipping(new MutableMultiset[AbsMolValue[_]](prevValMap))
                        .filter(inputInfo.admitsValue)

                        .map { v ⇒
                          foundValues(i) = v
                          repeatedVals.updated(siteMolIndex, v :: prevValMap)
                        }
                    } else {
                      // This is not a repeated molecule, so `repeatedVals` is unchanged but `foundValues` is mutated in place.
                      moleculesPresent(siteMolIndex)
                        .allValues
                        .filter(inputInfo.admitsValue)
                        .map { v ⇒
                          foundValues(i) = v
                          repeatedVals
                        }
                    }
                  }
                )

              case ConstrainGuard(i) ⇒
                val guard = info.crossGuards(i)
                Some(repeatedMolValuesStream.filter { _ ⇒
                  guard.cond.isDefinedAt(guard.indices.map(i ⇒ foundValues(i).moleculeValue).toList)
                })

              case CloseGroup ⇒
                // If the stream is empty, we will return `None` here and terminate the "flat fold".
                // Otherwise, we take the first available `MolVals` value and set the accumulator back to the initial stream.
                repeatedMolValuesStream.toIterable.headOption.map(_ ⇒ initStream)
            }
          }
          found.nonEmpty
        }
      }
    // Returning the final result now.
    if (foundResult)
      Some((r, foundValues))
    else
      None
  }

  /** Check if the current reaction is allowed to emit a static molecule.
    * If so, remove the emitter from the mutable set.
    * The information about the current reaction is kept in the thread info.
    *
    * @param m A static molecule emitter.
    * @return `()` if the thread is allowed to emit that molecule. Otherwise, an exception is thrown with a refusal reason message.
    */
  private def allowEmittedStaticMolOrThrow(m: Molecule, throwError: String => Unit): Unit = {
    currentReactionInfo.foreach { info =>
      if (!info.maybeEmit(m.siteIndex)) {
        val refusalReason =
          if (!info.couldEmit(m.siteIndex))
            s"because this reaction {$info} does not consume it"
          else s"because this reaction {$info} already emitted it"
        throwError(refusalReason)
      } // otherwise we are ok
    }
    if (currentReactionInfo.isEmpty)
      throwError("because this thread does not run a chemical reaction")
  }

  /** This variable is true only at the initial stage of building the reaction site,
    * when static reactions are run (on the same thread as the `site()` call) in order to emit the initial static molecules.
    */
  private var nowEmittingStaticMols = false

  /** This is computed only once, when the first molecule is emitted into this reaction site.
    * If, at that time, there are any molecules that are still unbound but used as output by this reaction site, we report an error.
    * In this way, errors can be signalled as early as possible.
    *
    * This `val` does not need to be recomputed because this error is permanent (would be a compile-time error in JoCaml).
    */
  private lazy val findUnboundOutputMolecules: Boolean = unboundOutputMolecules(nonStaticReactions).nonEmpty

  /** Emit a molecule with a value into the soup.
    *
    * This method is run on the thread that emits the molecule. This method is common for blocking and non-blocking molecules.
    *
    * @param mol      Molecule emitter.
    * @param molValue Value of the molecule, wrapped in an instance of [[AbsMolValue]]`[T]` class.
    * @tparam T Type of the molecule value.
    */
  private def emit[T](mol: Molecule, molValue: AbsMolValue[T]): Unit = {
    if (findUnboundOutputMolecules) {
      val moleculesString = unboundOutputMoleculesString(nonStaticReactions)
      val noReactionMessage = s"In $this: As $mol($molValue) is emitted, some reactions may emit molecules ($moleculesString) that are not bound to any reaction site"
      throw new ExceptionNoReactionSite(noReactionMessage)
    }
    else if (sitePool.isInactive) {
      val noPoolMessage = s"In $this: Cannot emit molecule $mol($molValue) because site pool is not active"
      throw new ExceptionNoSitePool(noPoolMessage)
    }
    else if (!Thread.currentThread().isInterrupted) {
      if (nowEmittingStaticMols) {
        // Emit them on the same thread, and do not start any reactions.
        if (mol.isStatic) {
          addToBag(mol, molValue)
          mol.asInstanceOf[M[T]].assignStaticMolVolatileValue(molValue)
        } else {
          val refusingEmitAsStatic = s"In $this: Refusing to emit molecule $mol($molValue) initially as static (must be a non-blocking molecule)"
          throw new ExceptionEmittingStaticMol(refusingEmitAsStatic)
        }
      } else {
        // For pipelined molecules, check whether their value satisfies at least one of the conditions (if any conditions are present).
        // (If no condition is satisfied, we will not emit this value for a pipelined molecule.)
        // For non-pipelined molecules, `admitsValue` will be identically `true`.
        val admitsValue = !mol.isPipelined ||
          // TODO: could optimize this, since `pipelinedMolecules` is only used to check `admitsValue`
          pipelinedMolecules.get(mol.siteIndex).forall(infos ⇒ infos.isEmpty || infos.exists(_.admitsValue(molValue)))
        if (mol.isStatic) {
          // Check permission and throw exceptions on errors, but do not add anything to moleculesPresent and do not yet set the volatile value.
          // If successful, this will modify the thread's copy of `ChymystThreadInfo` to register the fact that we emitted that static molecule.
          allowEmittedStaticMolOrThrow(mol, refusalReason =>
            throw new ExceptionEmittingStaticMol(s"In $this: Refusing to emit static molecule $mol($molValue) $refusalReason")
          )
          // If we are here, we are allowed to emit this static molecule.
          if (admitsValue)
            mol.asInstanceOf[M[T]].assignStaticMolVolatileValue(molValue)
        }
        // If we are here, we are allowed to emit.
        // But will not emit if the pipeline does not admit the value.
        if (admitsValue) {
          addToBag(mol, molValue)

          lazy val emitMoleculeMessage = s"Debug: In $this: emitting $mol($molValue), now have molecules [${moleculeBagToString(moleculesPresent)}]"
          if (logLevel > 0) println(emitMoleculeMessage)
          if (schedulingNeeded(mol))
            sitePool.runRunnable(emissionRunnable(mol))
          //          else if (logLevel > 1) println(s"Debug: In $this: not scheduling emissionRunnable") // This is too verbose.
        } else {
          reportError(s"In $this: Refusing to emit${if (mol.isStatic) " static" else ""} pipelined molecule $mol($molValue) since its value fails the relevant conditions")
        }
      }
    }
  }

  /** Compute a map of molecule counts in the soup. This is potentially very expensive if there are many molecules present.
    * This function is called only once, after emitting the initial static molecules.
    *
    * @return For each molecule present in the soup, the map shows the number of copies present.
    */
  private def getMoleculeCountsAfterInitialStaticEmission: Map[Molecule, Int] =
    moleculesPresent.indices
      .flatMap(i => if (moleculesPresent(i).isEmpty)
        None
      else
        Some((moleculeAtIndex(i), moleculesPresent(i).size))
      )(breakOut)

  private def addToBag(mol: Molecule, molValue: AbsMolValue[_]): Unit =
    moleculesPresent(mol.siteIndex).add(molValue)

  private def removeFromBag(mol: Molecule, molValue: AbsMolValue[_]): Boolean = {
    val lockForRemovingOneMolecule = MoleculeValueLock(counts = Map(mol.siteIndex → 1), molecules = Set())
    acquireLock(lockForRemovingOneMolecule) && {
      val status = moleculesPresent(mol.siteIndex).remove(molValue)
      releaseLock(lockForRemovingOneMolecule)
      status
    }
  }

  private[jc] def moleculeBagToString(bags: Array[MutableBag[AbsMolValue[_]]]): String =
    Core.moleculeBagToString(bags.indices
      .flatMap(i => if (bags(i).isEmpty)
        None
      else
        Some((moleculeAtIndex(i), bags(i).getCountMap))
      )(breakOut): Map[Molecule, Map[AbsMolValue[_], Int]]
    )

  // Remove a blocking molecule if it is present.
  private def removeBlockingMolecule[T, R](bm: B[T, R], blockingMolValue: BlockingMolValue[T, R]): Unit = {
    removeFromBag(bm, blockingMolValue)
    lazy val removeBlockingMolMessage = s"Debug: $this removed $bm($blockingMolValue) on thread pool $sitePool, now have molecules [${moleculeBagToString(moleculesPresent)}]"
    if (logLevel > 0) println(removeBlockingMolMessage)
  }

  /** Common code for [[emitAndAwaitReply]] and [[emitAndAwaitReplyWithTimeout]].
    *
    * @param timeoutOpt        Timeout value in nanoseconds, or None if no timeout is requested.
    * @param bm                A blocking molecule to be emitted.
    * @param v                 Value that the newly emitted molecule should carry.
    * @param replyValueWrapper The reply value wrapper for the blocking molecule.
    * @tparam T Type of the value carried by the blocking molecule.
    * @tparam R Type of the reply value.
    * @return Reply status for the reply action.
    */
  private def emitAndAwaitReplyInternal[T, R](timeoutOpt: Option[Long], bm: B[T, R], v: T, replyValueWrapper: AbsReplyEmitter[T, R]): ReplyStatus = {
    val blockingMolValue = BlockingMolValue(v, replyValueWrapper)
    emit[T](bm, blockingMolValue)
    val timedOut: Boolean = !BlockingIdle {
      replyValueWrapper.acquireSemaphoreForEmitter(timeoutNanos = timeoutOpt)
    }
    // We might have timed out, in which case we need to forcibly remove the blocking molecule from the soup.
    if (timedOut) {
      removeBlockingMolecule(bm, blockingMolValue)
      blockingMolValue.replyValue.setTimedOut()
    }
    replyValueWrapper.releaseSemaphoreForReply()
    replyValueWrapper.getReplyStatus
  }

  // Adding a blocking molecule may trigger at most one reaction and must return a value of type R.
  // We must make this a blocking call, so we acquire a semaphore (with or without timeout).
  private def emitAndAwaitReply[T, R](bm: B[T, R], v: T, replyValueWrapper: AbsReplyEmitter[T, R]): R = {
    // check if we had any errors, and that we have a result value
    emitAndAwaitReplyInternal(timeoutOpt = None, bm, v, replyValueWrapper) match {
      case ErrorNoReply(message) =>
        throw new Exception(message)
      case HaveReply(res) =>
        res.asInstanceOf[R] // Cannot guarantee type safety due to type erasure of `R`.
    }
  }

  // This is a separate method because it has a different return type than [[emitAndAwaitReply]].
  private def emitAndAwaitReplyWithTimeout[T, R](timeout: Long, bm: B[T, R], v: T, replyValueWrapper: AbsReplyEmitter[T, R]):
  Option[R] = {
    // check if we had any errors, and that we have a result value
    emitAndAwaitReplyInternal(timeoutOpt = Some(timeout), bm, v, replyValueWrapper) match {
      case ErrorNoReply(message) =>
        throw new Exception(message)
      case HaveReply(res) =>
        if (replyValueWrapper.isTimedOut)
          None
        else
          Some(res.asInstanceOf[R]) // Cannot guarantee type safety due to type erasure of `R`.
    }
  }

  /** This is called once, when the reaction site is first declared using the [[site]] call.
    * It is called on the thread that calls [[site]].
    *
    * @return A tuple containing the molecule value bags, and a list of warning and error messages.
    */
  private def initializeReactionSite() = {
    // Set the RS info on all input molecules in this reaction site.
    knownMolecules.foreach { case (mol, (index, valType)) ⇒
      // Assign the value bag.
      val pipelined = pipelinedMolecules contains index
      val simpleType = simpleTypes contains valType
      moleculesPresent(index) = if (simpleType && !pipelined)
        new MutableMapBag[AbsMolValue[_]]()
      else
        new MutableQueueBag[AbsMolValue[_]]()

      // Assign the RS info on molecule or throw exception on error.
      mol.isBoundToAnotherReactionSite(this) match {
        case Some(otherRS) =>
          throw new ExceptionMoleculeAlreadyBound(s"Molecule $mol cannot be used as input in $this since it is already bound to $otherRS")
        case None ⇒
          mol.setReactionSiteInfo(this, index, valType, pipelined)
      }
    }

    // Add output reactions to molecules that may be bound to other reaction sites later.
    nonStaticReactions.foreach { r =>
      r.info.outputs.foreach(_.molecule.addEmittingReaction(r))
    }

    // Perform static analysis.
    val foundWarnings = findStaticMolWarnings(staticMolDeclared, nonStaticReactions) ++ findGeneralWarnings(nonStaticReactions)

    val foundErrors = findStaticMolDeclarationErrors(staticReactions) ++
      findStaticMolErrors(staticMolDeclared, nonStaticReactions) ++
      findGeneralErrors(nonStaticReactions)

    val staticDiagnostics = WarningsAndErrors(foundWarnings, foundErrors, s"$this")

    // This is necessary to prevent the static reactions from running in case there are already errors.
    if (staticDiagnostics.noErrors) {
      // Emit static molecules now.
      // This must be done without starting any reactions that might consume these molecules.
      // So, we set the flag `nowEmittingStaticMols`, which will prevent other reactions from starting.
      // Note: mutable variables are OK since this is on the same thread as the call to `site`, so it's guaranteed to be single-threaded!
      nowEmittingStaticMols = true
      staticReactions.foreach { reaction =>
        // It is OK that the argument is `null` because static reactions match on the wildcard: { case _ => ... }
        reaction.body.apply(null.asInstanceOf[ReactionBodyInput])
      }
      nowEmittingStaticMols = false

      val staticMolsActuallyEmitted = getMoleculeCountsAfterInitialStaticEmission
      val staticMolsEmissionWarnings = findStaticMolsEmissionWarnings(staticMolDeclared, staticMolsActuallyEmitted)
      val staticMolsEmissionErrors = findStaticMolsEmissionErrors(staticMolDeclared, staticMolsActuallyEmitted)

      val staticMolsDiagnostics = WarningsAndErrors(staticMolsEmissionWarnings, staticMolsEmissionErrors, s"$this")
      staticDiagnostics ++ staticMolsDiagnostics
    } else staticDiagnostics
  }

  /** Create the site-wide index map for all molecules bound to this reaction site.
    * This computation determines the site-wide index for each molecule.
    */
  private val knownMolecules: Map[Molecule, (Int, Symbol)] = {
    nonStaticReactions
      .flatMap(_.inputMoleculesSortedAlphabetically)
      .distinct // Take all input molecules from all reactions; arrange them in a single list.
      .sortBy(_.name)
      .zipWithIndex
      .map { case (mol, index) ⇒
        val valType = nonStaticReactions.view
          .map(_.info.inputs)
          .flatMap(_.find(_.molecule === mol))
          .headOption
          .map(_.valType)
          .getOrElse("<unknown>".toScalaSymbol)

        (mol, (index, valType))
      }(breakOut)
  }

  /** Determine whether the molecule with site-wide index `i` can be pipelined, and return the corresponding input information.
    * A molecule can be pipelined only if its input conditions are completely independent of all other molecules.
    *
    * @param i Site-wide index of a molecule.
    * @return `None` if the molecule is not pipelined. Otherwise return the set of [[InputMoleculeInfo]] values
    *         describing that molecule's conditionals in all reactions consuming that molecule.
    *         The set is empty if the molecule has no conditionals in any of the consuming reactions.
    */
  private def infosIfPipelined(i: Int): Option[Set[InputMoleculeInfo]] = {
    consumingReactions(i)
      .flatFoldLeft[(Set[InputMoleculeInfo], Boolean, Boolean)]((Set(), false, true)) {
      case (acc, r) ⇒
        val (prevConds, prevHaveOtherInputs, isFirstReaction) = acc
        val haveOtherInputs = r.info.inputs.exists(_.molecule =!= moleculeAtIndex(i))
        val inputsForThisMolecule = r.info.inputs.filter(_.molecule === moleculeAtIndex(i))

        // There should be no cross-molecule conditions / guards involving this molecule or any of its reaction partners; otherwise, it cannot be pipelined.
        // So, if this molecule is nonlinear (`inputsForThisMolecule.length > 1`) there should be no conditions on its values.
        if (inputsForThisMolecule.map(_.index).toSet subsetOf r.info.independentInputMolecules) {
          // Get the conditions for this molecule. There should be no conditions when the molecule is repeated, and at most one otherwise.
          val thisConds = inputsForThisMolecule.filterNot(_.flag.isIrrefutable).toSet
          // If we have no previous other inputs and no current other inputs, we can concatenate the conditions and we are done.
          val newHaveOtherInputs = haveOtherInputs || prevHaveOtherInputs
          if (newHaveOtherInputs) {
            // If we have other inputs either now, or previously, or both,
            // we do not fail only if the previous condition is exactly the same as the current one, or if this is the first condition we are considering.
            if (isFirstReaction || prevConds.map(_.sha1) === thisConds.map(_.sha1))
              Some((thisConds, newHaveOtherInputs, false))
            else
              None
          } else {
            Some((prevConds ++ thisConds, newHaveOtherInputs, false))
          }
        } else {
          None
        }
    }.map(_._1)
  }

  /** Map the site-wide index to molecule emitter. This is used often.
    *
    */
  private val moleculeAtIndex: Map[Int, Molecule] =
    knownMolecules.map { case (mol, (i, _)) ⇒ (i, mol) }(breakOut)

  /** For each site-wide molecule index, this array holds the array of reactions consuming that molecule.
    *
    */
  private val consumingReactions: Array[Array[Reaction]] =
    Array.tabulate(knownMolecules.size)(i ⇒ getConsumingReactions(moleculeAtIndex(i)))

  // This must be lazy because it depends on site-wide molecule indices, which are known late.
  // The inner array contains site-wide indices for reaction input molecules; the outer array is also indexed by site-wide molecule indices.
  //  private lazy val relatedMolecules: Array[Array[Int]] = Array.tabulate(knownMolecules.size)(i ⇒ consumingReactions(i).flatMap(_.inputMoleculesSet.map(_.index)).distinct)

  /** For each (site-wide) molecule index, the corresponding set of [[InputMoleculeInfo]]s contains only the infos with nontrivial conditions for the molecule value.
    * This is used to assign the pipelined status of a molecules and also to obtain the conditional for that molecule's value.
    */
  private val pipelinedMolecules: Map[Int, Set[InputMoleculeInfo]] =
    moleculeAtIndex.flatMap { case (index, _) ⇒
      infosIfPipelined(index).map(c ⇒ (index, c))
    }

  /** For each (site-wide) molecule index, the corresponding array element represents the container for
    * that molecule's present values.
    * That container will be mutated as molecules arrive or leave the reaction site.
    * The specific type of the container - [[MutableMapBag]] or [[MutableQueueBag]]
    * - will be chosen separately for each molecule when this array is initialized.
    */
  private val moleculesPresent: MoleculeBagArray = new Array(knownMolecules.size)

  /** Print warning messages and throw exception if the initialization of this reaction site caused errors.
    *
    * @return Warnings and errors as a [[WarningsAndErrors]] value. If errors were found, throws an exception and returns nothing.
    */
  private[jc] def checkWarningsAndErrors(): WarningsAndErrors = diagnostics.checkWarningsAndErrors()

  // This call should be done at the very end, after all other values are computed, because it depends on `pipelinedMolecules`, `consumingReactions`, `knownMolecules`, and other computed values.
  private val diagnostics: WarningsAndErrors = initializeReactionSite()
}

final case class WarningsAndErrors(warnings: Seq[String], errors: Seq[String], reactionSite: String) {
  def noErrors: Boolean = errors.isEmpty

  def checkWarningsAndErrors(): WarningsAndErrors = {
    if (warnings.nonEmpty) println(s"In $reactionSite: ${warnings.mkString("; ")}")
    if (errors.nonEmpty) throw new Exception(s"In $reactionSite: ${errors.mkString("; ")}")
    this
  }

  def hasErrorsOrWarnings: Boolean = warnings.nonEmpty || errors.nonEmpty

  def ++(other: WarningsAndErrors): WarningsAndErrors =
    copy(warnings = warnings ++ other.warnings, errors = errors ++ other.errors)
}

/** Exceptions of this class are thrown on error conditions due to incorrect usage of `Chymyst Core`.
  *
  * @param message Description of the error.
  */
private[jc] sealed class ExceptionInChymyst(message: String) extends Exception(message)

private[jc] final class ExceptionNoReactionSite(message: String) extends ExceptionInChymyst(message)

private[jc] final class ExceptionMoleculeAlreadyBound(message: String) extends ExceptionInChymyst(message)

private[jc] final class ExceptionNoSitePool(message: String) extends ExceptionInChymyst(message)

private[jc] final class ExceptionEmittingStaticMol(message: String) extends ExceptionInChymyst(message)

private[jc] final class ExceptionNoReactionPool(message: String) extends ExceptionInChymyst(message)

private[jc] final class ExceptionNoWrapper(message: String) extends ExceptionInChymyst(message)

private[jc] final class ExceptionWrongInputs(message: String) extends ExceptionInChymyst(message)

private[jc] final class ExceptionNoStaticMol(message: String) extends ExceptionInChymyst(message)

/** Molecules do not have direct access to the reaction site object.
  * Molecules will call only functions from this wrapper.
  * This is intended to make it impossible to access the reaction site object via reflection on private fields in the Molecule class.
  *
  * Specific values of [[ReactionSiteWrapper]] are created by [[ReactionSite.makeWrapper]]
  * and assigned to molecule emitters by [[Molecule.setReactionSiteInfo]].
  */
private[jc] final class ReactionSiteWrapper[T, R](
  override val toString: String,
  val logSoup: () => String,
  val setLogLevel: Int => Unit,
  val isStatic: () => Boolean,
  val emit: (Molecule, AbsMolValue[T]) => Unit,
  val emitAndAwaitReply: (B[T, R], T, AbsReplyEmitter[T, R]) => R,
  val emitAndAwaitReplyWithTimeout: (Long, B[T, R], T, AbsReplyEmitter[T, R]) => Option[R],
  val consumingReactions: Array[Reaction],
  val sameReactionSite: ReactionSite => Boolean
)

private[jc] object ReactionSiteWrapper {
  def noReactionSite[T, R](m: Molecule): ReactionSiteWrapper[T, R] = {
    def exception: Nothing = throw new ExceptionNoReactionSite(s"Molecule $m is not bound to any reaction site")

    new ReactionSiteWrapper(
      toString = "",
      logSoup = () => exception,
      setLogLevel = _ => exception,
      isStatic = () ⇒ exception,
      emit = (_: Molecule, _: AbsMolValue[T]) => exception,
      emitAndAwaitReply = (_: B[T, R], _: T, _: AbsReplyEmitter[T, R]) => exception,
      emitAndAwaitReplyWithTimeout = (_: Long, _: B[T, R], _: T, _: AbsReplyEmitter[T, R]) => exception,
      consumingReactions = Array[Reaction](),
      sameReactionSite = _ => exception
    )
  }
}

private[jc] final case class MoleculeValueLock(
  counts: Map[Int, Int] = Map(),
  molecules: Set[Int] = Set(),
  semaphore: Semaphore = new Semaphore(0, false)
) {
  val usedMoleculesSet: Set[Int] = counts.keySet ++ molecules
  val usedMoleculesSeq: IndexedSeq[Int] = usedMoleculesSet.toIndexedSeq.sorted

  @volatile var acquireStatus: Boolean = false

  def grantSemaphore(status: Boolean): Unit = {
    acquireStatus = status
    semaphore.release(1)
  }

  def acquireSemaphore(): Unit = semaphore.acquire(1)
}
