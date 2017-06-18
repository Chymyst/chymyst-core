package io.chymyst.jc

import java.util.concurrent.atomic.AtomicIntegerArray

import io.chymyst.jc.Core._
import io.chymyst.jc.StaticAnalysis._

import scala.annotation.tailrec
import scala.collection.breakOut
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scalaxy.streams.optimize
import scalaxy.streams.strategy.aggressive

/** Represents the reaction site, which holds one or more reaction definitions (chemical laws).
  * At run time, the reaction site maintains a bag of currently available input molecules and runs reactions.
  * The user will never see any instances of this class.
  *
  * @param reactions    List of reactions as defined by the user.
  * @param reactionPool The thread pool on which reactions will be scheduled.
  */
private[jc] final class ReactionSite(reactions: Seq[Reaction], reactionPool: Pool) {

  private val initTime = System.nanoTime()

  private val (staticReactions, nonStaticReactions) = reactions.toArray.partition(_.info.isStatic)

  /** Create the site-wide index map for all molecules bound to this reaction site.
    * This computation determines the site-wide index for each input molecule.
    */
  private val knownInputMolecules: Map[MolEmitter, (MolSiteIndex, Symbol)] = optimize {
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

        (mol, (MolSiteIndex(index), valType))
      }(breakOut)
  }

  /** For each (site-wide) molecule index, the corresponding array element represents the container for
    * that molecule's present values.
    * That container will be mutated as molecules arrive or leave the reaction site.
    * The specific type of the container - [[MutableMapBag]] or [[MutableQueueBag]]
    * - will be chosen separately for each molecule when this array is initialized.
    */
  private val moleculesPresent: MoleculeBagArray = new Array(knownInputMolecules.size)

  /** Each reaction site has a permanent unique ID number.
    * It is used to detect identical reaction sites.
    */
  private[jc] val id: ReactionSiteId = nextReactionSiteId

  /** The table of statically declared static molecules and their multiplicities.
    * Only non-blocking molecules can be static.
    * Static molecules are emitted by "static reactions" (i.e. { case _ => ...}), which are run only once at the reaction site activation time.
    * This list may be incorrect if the static reaction code emits molecules conditionally or emits many copies.
    * So, the code (1 to 10).foreach (_ => s() ) will put (s -> 1) into `staticMolDeclared` but (s -> 10) into `staticMolsEmitted`.
    */
  private[jc] val staticMolDeclared: Map[MolEmitter, Int] = staticReactions.map(_.info.outputs)
    .flatMap(_.map(_.molecule).filterNot(_.isBlocking))
    .groupBy(identity)
    .mapValues(_.length)

  private val toStringLimit = 1024

  override val toString: ReactionSiteString = {
    val raw = s"Site{${nonStaticReactions.map(_.toString).sorted.mkString("; ")}}".take(toStringLimit + 1)
    ReactionSiteString(
      if (raw.length > toStringLimit)
        raw.substring(0, toStringLimit) + "..."
      else raw
    )
  }

  /** The sha1 hash sum of the entire reaction site, computed from sha1 of each reaction.
    * The sha1 hash of each reaction is computed from the Scala syntax tree of the reaction's source code.
    * The result is implementation-dependent and is guaranteed to be the same only for reaction sites compiled from exactly the same source code with the same version of Scala compiler.
    */
  //  private lazy val sha1 = getSha1String(knownReactions.map(_.info.sha1).sorted.mkString(","))

  private[jc] def printBag: String = {
    val moleculesPrettyPrinted = if (moleculesPresent.exists(!_.isEmpty))
      s"Molecules: $moleculesPresentToString"
    else "No molecules"

    s"$toString\n$moleculesPrettyPrinted"
  }

  /** Optimization for scheduler closures. We would like to avoid scheduling closures that check whether reactions can be run with molecule `mol`
    * if such a closure is already scheduled and is not yet running.
    * This is safe because scheduling closures will keep trying to schedule new reactions with `mol` while it is possible.
    * This optimization avoids expensive thread switching while scheduling and starting a new closure.
    *
    * The array `needScheduling` contains 0 or 1 for each site-wide molecule index.
    * The value 1 represents the fact that a scheduling closure is now running and will check new reactions for this molecule,
    * so no new scheduling closures need to be run for this molecule now.
    */
  private lazy val needScheduling: AtomicIntegerArray = new AtomicIntegerArray(knownInputMolecules.size)

  private val NEED_TO_SCHEDULE = 0

  private val NO_NEED_TO_SCHEDULE = 1

  private def setNoNeedToSchedule(mol: MolEmitter): Unit = needScheduling.set(mol.siteIndex, NO_NEED_TO_SCHEDULE)

  private def setNeedToSchedule(mol: MolEmitter): Unit = needScheduling.set(mol.siteIndex, NEED_TO_SCHEDULE)

  private def isSchedulingNeeded(mol: MolEmitter): Boolean = needScheduling.compareAndSet(mol.siteIndex, NEED_TO_SCHEDULE, NO_NEED_TO_SCHEDULE)

  /** We only need to find one reaction whose input molecules are available.
    * For this, we use the special method [[ArrayWithExtraFoldOps.findAfterMap]].
    * The value `foundReactionsAndInputs` will indicate the selected reaction and its input molecule values.
    */
  @tailrec
  private def decideReactionsForNewMolecule(mol: MolEmitter): Unit = {
    // TODO: optimize: precompute all related molecules in ReactionSite? (what exactly to precompute??)
    setNeedToSchedule(mol)
    // This option value will be non-empty if we have a reaction with some input molecules that all have admissible values for that reaction.
    val candidateReactions = consumingReactions(mol.siteIndex)
    val foundReactionAndInputs: Option[(Reaction, InputMoleculeList)] = candidateReactions.indices
      .findAfterMap(ind ⇒ optimize {
        val thisReaction = candidateReactions(ind)
        // Optimization: ignore reactions that do not have all the required molecules.
        if (thisReaction.inputMoleculesSet.exists(mol ⇒ moleculesPresent(mol.siteIndex).isEmpty) ||
          !thisReaction.info.guardPresence.staticGuardHolds())
          None
        else {
          val result = findInputMolecules(thisReaction, moleculesPresent)
          // If we have found a reaction that can be run; need to remove its input molecule values from their bags.
          result.map { thisInputList ⇒
            thisReaction.info.inputIndices.foreach { i ⇒
              val molValue = thisInputList(i)
              val mol = thisReaction.info.inputs(i).molecule
              // This error (molecule value was found for a reaction but is now not present) indicates a bug in this code, which should already manifest itself in failing tests! We can't cover this error by tests if the code is correct.
              if (!internalRemoveFromBag(mol, molValue)) reportError(s"Error: In $this: Internal error: Failed to remove molecule $mol($molValue) from its bag; molecule index ${mol.siteIndex}, bag ${moleculesPresent(mol.siteIndex)}", printToConsole = true)
            }
            setNoNeedToSchedule(mol)

            // Shuffle this reaction to the beginning of consumingReactions.
            if (ind > 0) {
              val r = candidateReactions(0)
              candidateReactions(0) = thisReaction
              candidateReactions(ind) = r
            }

            (thisReaction, thisInputList)
          }
        }

      })
    foundReactionAndInputs match {
      case Some((thisReaction, usedInputs)) =>
        // Build a closure out of the reaction, and run that closure on the reaction's thread pool.
        val poolForReaction = thisReaction.threadPool.getOrElse(reactionPool)
        if (poolForReaction.isInactive) {
          reportError(s"In $this: cannot run reaction {${thisReaction.info}} since reaction pool is not active; input molecules ${reactionInputsToString(thisReaction, usedInputs)} were consumed and not emitted again", printToConsole = false)
          // In this case, we do not attempt to schedule a reaction. However, input molecules were consumed and not emitted again.
        } else if (!Thread.currentThread().isInterrupted) {

          // Schedule the reaction now. Provide reaction info to the thread.

          scheduleReaction(thisReaction, usedInputs, poolForReaction)
          reactionPool.reporter.reactionScheduled(id, toString, mol.siteIndex, mol.toString, thisReaction.info.toString, reactionInputsToString(thisReaction, usedInputs), debugRemainingMolecules)
          // Signal success of scheduler decision.
          thisReaction.inputMoleculesSet.foreach(_.fulfillWhenDecidedPromise(mol.toString))
          // The scheduler loops, trying to run another reaction with the same molecule, if possible. This is required for correct operation.
          decideReactionsForNewMolecule(mol)
        }
      case None ⇒
        mol.failWhenDecidedPromise()
        reactionPool.reporter.noReactionScheduled(id, toString, mol.siteIndex, mol.toString, debugRemainingMolecules)
    }
  }

  private def debugRemainingMolecules: String = {
    if (moleculesPresent.forall(_.isEmpty))
      ""
    else
      s"$moleculesPresentToString"
  }

  private def scheduleReaction(reaction: Reaction, usedInputs: InputMoleculeList, poolForReaction: Pool): Unit =
    poolForReaction.runReaction(reaction.info.toString, reactionClosure(reaction, usedInputs, poolForReaction: Pool))

  /** This [[Runnable]] will be run on a single-threaded reaction site pool, therefore we do not need to synchronize anything.
    *
    * @param mol A molecule that was recently emitted.
    * @return A new [[Runnable]] that will looking for reactions that consume the molecule `mol`.
    */
  private def emissionRunnable(mol: MolEmitter): Runnable = { () ⇒
    reactionPool.reporter.schedulerStep(id, toString, mol.siteIndex, mol.toString, moleculesPresentToString)
    decideReactionsForNewMolecule(mol)
  }

  private def reportError(message: String, printToConsole: Boolean): Unit =
    reactionPool.reporter.chymystRuntimeError(id, toString, message, printToConsole)

  /** This closure will be run on the reaction thread pool to start a new reaction.
    *
    * @param thisReaction Reaction to run.
    * @param usedInputs   Molecules (with values) that are consumed by the reaction.
    */
  private def reactionClosure(thisReaction: Reaction, usedInputs: InputMoleculeList, poolForReaction: Pool): Unit = {
    reactionPool.reporter.reactionStarted(id, toString, Thread.currentThread().getName, thisReaction.info.toString, reactionInputsToString(thisReaction, usedInputs))
    val initNs = System.nanoTime()
    lazy val reactionInputsDebugString = reactionInputsToString(thisReaction, usedInputs)
    val exitStatus: ReactionExitStatus = try {
      setReactionInfoInThread(thisReaction.info)

      usedInputs.foreach(_.fulfillWhenConsumedPromise())

      ///////////
      // At this point, we apply the reaction body to its input molecules. This runs the reaction.
      //////////

      thisReaction.body.apply(ReactionBodyInput(index = usedInputs.length - 1, inputs = usedInputs))

      clearReactionInfoInThread()
      // If we are here, we had no exceptions during evaluation of reaction body.
      ReactionExitSuccess
    } catch {
      // Catch various exceptions that occurred while running the reaction body.
      case e: ExceptionInChymyst =>
        // Running the reaction body produced an exception that is internal to `Chymyst Core`.
        // We should not try to recover from this; it is either an error on user's part or a bug in `Chymyst Core`.
        val message = s"In $this: Reaction {${thisReaction.info}} with inputs [$reactionInputsDebugString] produced an exception internal to Chymyst Core. Retry run was not scheduled. Message: ${e.getMessage}"
        reportError(message, printToConsole = true)
        ReactionExitFailure(message)

      case e: Exception =>
        // Running the reaction body produced an exception. Note that the exception has killed a thread.
        // We will now schedule this reaction again if retry was requested. Hopefully, no side-effects or output molecules were produced so far.
        val (status, retryMessage) =
          if (thisReaction.retry) {
            reactionPool.reporter.reactionRescheduled(id, toString, thisReaction.info.toString, reactionInputsDebugString, debugRemainingMolecules)
            scheduleReaction(thisReaction, usedInputs, poolForReaction)
            (ReactionExitRetryFailure(e.getMessage), " Retry run was scheduled.")
          }
          else (ReactionExitFailure(e.getMessage), " Retry run was not scheduled.")

        val generalExceptionMessage = s"In $this: Reaction {${thisReaction.info}} with inputs [$reactionInputsDebugString] produced ${e.getClass.getSimpleName}.$retryMessage Message: ${e.getMessage}"

        reportError(generalExceptionMessage, printToConsole = true)
        status
    }

    val elapsedNs = System.nanoTime() - initNs
    reactionPool.reporter.reactionFinished(id, toString, thisReaction.info.toString, reactionInputsToString(thisReaction, usedInputs), exitStatus, elapsedNs)

    // The reaction is finished. If it had any blocking input molecules, we check if any of them got no reply.
    if (thisReaction.info.hasBlockingInputs && usedInputs.exists(_.reactionSentNoReply)) {
      // For any blocking input molecules that have no reply, put an error message into them and reply with empty value to unblock the threads.

      // Compute error messages here in case we will need them later.
      val blockingMoleculesWithNoReply = optimize {
        usedInputs.zipWithIndex
          .filter(_._1.reactionSentNoReply)
          .map { case (_, i) ⇒ thisReaction.info.inputs(i).molecule }
          .toSeq.toOptionSeq
          .map(_.map(_.toString).sorted.mkString(", "))
      }

      // We will report all errors to each blocking molecule.
      val haveErrorsWithBlockingMolecules = blockingMoleculesWithNoReply.nonEmpty && exitStatus.reactionSucceededOrFailedWithoutRetry

      if (haveErrorsWithBlockingMolecules) {
        val message = blockingMoleculesWithNoReply.map { bmol ⇒
          s"In $this: Reaction {${thisReaction.info}} with inputs [$reactionInputsDebugString] finished without replying to $bmol${exitStatus.getMessage}"
        }.mkString("; ")
        reportError(message, printToConsole = false)
      }
    }
  }

  /** Find a set of input molecule values for a reaction. */
  private def findInputMolecules(r: Reaction, moleculesPresent: MoleculeBagArray): Option[InputMoleculeList] = optimize {
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
            molBag.headOption.filter(inputInfo.admitsValue) // For pipelined molecules, we take the first one; if condition fails, we treat that case as if no molecule is available.
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
            val molCount = r.moleculeIndexRequiredCounts(molSiteIndex)
            val molValuesFound = moleculesPresent(molSiteIndex).takeAny(molCount)
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
                        // TODO: move this to the skipping interface, restore Seq[T] as its argument?
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
                  guard.cond.isDefinedAt(molValuesForGuard(guard.indices, foundValues))
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
      Some(foundValues)
    else
      None
  }

  @tailrec
  private def molValuesForGuardRec(indices: Array[Int], foundValues: Array[AbsMolValue[_]], i: Int, acc: List[Any]): List[Any] = {
    val newAcc = foundValues(indices(i)).moleculeValue :: acc
    if (i === 0) newAcc
    else molValuesForGuardRec(indices, foundValues, i - 1, newAcc)
  }

  private def molValuesForGuard(indices: Array[Int], foundValues: Array[AbsMolValue[_]]): List[Any] = {
    molValuesForGuardRec(indices, foundValues, indices.length - 1, Nil)
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
  private lazy val findUnboundOutputMolecules: Boolean = nonStaticReactions.exists(_.info.outputs.exists(!_.molecule.isBound))

  /** Emit a molecule with a value into the soup.
    *
    * This method is run on the thread that emits the molecule. This method is common for blocking and non-blocking molecules.
    *
    * @param mol      Molecule emitter.
    * @param molValue Value of the molecule, wrapped in an instance of [[AbsMolValue]]`[T]` class.
    * @tparam T Type of the molecule value.
    */
  private[jc] def emit[T](mol: MolEmitter, molValue: AbsMolValue[T]): Unit = {
    if (findUnboundOutputMolecules) {
      val moleculesString = unboundOutputMoleculesString(nonStaticReactions)
      val message = s"In $this: As $mol($molValue) is emitted, some reactions may emit molecules ($moleculesString) that are not bound to any reaction site"
      reportError(message, printToConsole = true)
      throw new ExceptionNoReactionSite(message)
    }
    else if (reactionPool.isInactive) {
      val message = s"In $this: Cannot emit molecule $mol($molValue) because reaction pool is not active"
      reportError(message, printToConsole = false)
      throw new ExceptionNoReactionPool(message)
    }
    else if (!Thread.currentThread().isInterrupted) {
      if (nowEmittingStaticMols) {
        // Emit them on the same thread as the site() call, and do not start any reactions.
        if (mol.isStatic) {
          addToBag(mol, molValue)
          mol.asInstanceOf[M[T]].assignStaticMolVolatileValue(molValue)
        } else {
          val message = s"In $this: Refusing to emit molecule $mol($molValue) initially as static (must be a non-blocking molecule)"
          reportError(message, printToConsole = true)
          throw new ExceptionEmittingStaticMol(message)
        }
      } else {
        // For pipelined molecules, check whether their value satisfies at least one of the conditions (if any conditions are present).
        // (If no condition is satisfied, we will not emit this value for a pipelined molecule.)
        // For non-pipelined molecules, `admitsValue` will be identically `true`.
        val admitsValue = !mol.isPipelined ||
          // TODO: could optimize this, since `pipelinedMolecules` is only used to check `admitsValue`. (optimize how??)
          pipelinedMolecules.get(mol.siteIndex).forall(infos ⇒ infos.isEmpty || infos.exists(_.admitsValue(molValue)))

        // If we are here, we are allowed to emit.
        // But will not emit if the pipeline does not admit the value.
        if (admitsValue) {
          if (mol.isStatic) {
            mol.asInstanceOf[M[T]].assignStaticMolVolatileValue(molValue)
          }
          /////////////
          // At this point, we emit the molecule.
          ////////////

          addToBag(mol, molValue)
          if (isSchedulingNeeded(mol))
            reactionPool.runScheduler(emissionRunnable(mol))

          // Perform debug activity now.
          mol.fulfillWhenEmittedPromise(molValue.moleculeValue)
          reactionPool.reporter.emitted(id, toString, mol.siteIndex, mol.toString, molValue.toString, moleculesPresentToString)
        } else {
          val message = s"In $this: Refusing to emit${if (mol.isStatic) " static" else ""} pipelined molecule $mol($molValue) since its value fails the relevant conditions"
          if (mol.isStatic)
            reportError(message, printToConsole = false)
          else
            reactionPool.reporter.omitPipelined(id, toString, mol.siteIndex, mol.toString, molValue.toString)
        }
      }
    }
  }

  /** Compute a map of molecule counts in the soup. This is potentially very expensive if there are many molecules present.
    * This function is called only once, after emitting the initial static molecules.
    *
    * @return For each molecule present in the soup, the map shows the number of copies present.
    */
  private def getMoleculeCountsAfterInitialStaticEmission: Map[MolEmitter, Int] =
    moleculesPresent.indices
      .flatMap(i => if (moleculesPresent(i).isEmpty)
        None
      else
        Some((moleculeAtIndex(i), moleculesPresent(i).size))
      )(breakOut)

  private def addToBag(mol: MolEmitter, molValue: AbsMolValue[_]): Unit = moleculesPresent(mol.siteIndex).add(molValue)

  private def internalRemoveFromBag(mol: MolEmitter, molValue: AbsMolValue[_]): Boolean = {
    moleculesPresent(mol.siteIndex).remove(molValue)
  }

  private[jc] def moleculesPresentToString: String =
    Core.moleculeBagToString(moleculesPresent.indices
      .flatMap(i => if (moleculesPresent(i).isEmpty)
        None
      else
        Some((moleculeAtIndex(i), moleculesPresent(i).getCountMap))
      )(breakOut): Map[MolEmitter, Map[AbsMolValue[_], Int]]
    )

  // Remove a blocking molecule if it is present.
  private def removeBlockingMolecule[T, R](bm: B[T, R], blockingMolValue: BlockingMolValue[T, R]): Unit = {
    if (internalRemoveFromBag(bm, blockingMolValue))
      reactionPool.reporter.removed(id, toString, bm.siteIndex, bm.toString, blockingMolValue.toString, moleculesPresentToString)
  }

  /** Common code for [[emitAndAwaitReply]] and [[emitAndAwaitReplyWithTimeout]].
    *
    * @param bm A blocking molecule to be emitted.
    * @param v  Value that the newly emitted molecule should carry.
    * @tparam T Type of the value carried by the blocking molecule.
    * @tparam R Type of the reply value.
    * @return Wrapper for the blocking molecule's value.
    */
  @inline private def emitAndCreateReplyEmitter[T, R](bm: B[T, R], v: T, useFuture: Boolean = false) = {
    val blockingMolValue = BlockingMolValue(v, new ReplyEmitter[T, R](useFuture))
    emit[T](bm, blockingMolValue)
    blockingMolValue
  }

  // Adding a blocking molecule may trigger at most one reaction and must return a value of type R.
  // We must make this a blocking call, so we acquire a semaphore (with or without timeout).
  @inline private[jc] def emitAndAwaitReply[T, R](bm: B[T, R], v: T): R = {
    BlockingIdle(bm.isSelfBlocking) {
      emitAndCreateReplyEmitter(bm, v).replyEmitter.reply.await
    }
  }

  // This is a separate method because it has a different return type than [[emitAndAwaitReply]].
  @inline private[jc] def emitAndAwaitReplyWithTimeout[T, R](timeout: Duration, bm: B[T, R], v: T): Option[R] = {
    val bmv = emitAndCreateReplyEmitter(bm, v)
    val result = BlockingIdle(bm.isSelfBlocking) {
      bmv.replyEmitter.reply.await(timeout)
    }
    if (result.isEmpty) {
      reactionPool.reporter.replyTimedOut(id, toString, bm.siteIndex, bm.toString, timeout)
      // The emitting process has waited but did not get any reply value.
      removeBlockingMolecule(bm, bmv)
    } else {
      reactionPool.reporter.replyReceived(id, toString, bm.siteIndex, bm.toString, result.toString)
    }
    result
  }

  @inline private[jc] def emitAndGetFutureReply[T, R](bm: B[T, R], v: T): Future[R] = {
    emitAndCreateReplyEmitter(bm, v, useFuture = true).replyEmitter.reply.getFuture
  }

  /** This is called once, when the reaction site is first declared using the [[site]] call.
    * It is called on the thread that calls [[site]].
    *
    * @return A tuple containing the molecule value bags, and a list of warning and error messages.
    */
  private def initializeReactionSite() = optimize {
    /** Find blocking molecules whose emitting reactions are all in a single thread pool. These emissions are potential deadlock threats for that pool, especially for a [[FixedPool]]. */
    val selfBlockingMols: Map[MolEmitter, Pool] =
      knownInputMolecules
        .map { case (mol, (i, _)) ⇒ (mol, (consumingReactions(i).map(_.threadPool.getOrElse(reactionPool)).toSet, mol.isBlocking)) }
        .filter { case (_, (pools, isBlocking)) ⇒ isBlocking && pools.size === 1 }
        .flatMap { case (mol, (pools, _)) ⇒ pools.headOption.map(pool ⇒ (mol, pool)) }(breakOut)

    // Set the RS info on all input molecules in this reaction site.
    knownInputMolecules.foreach { case (mol, (siteIndex, valType)) ⇒
      // Assign the value bag.
      val pipelined = pipelinedMolecules contains siteIndex
      val simpleType = simpleTypes contains valType
      val unitType = valType === 'Unit
      val useMapBag = unitType || (simpleType && !pipelined)
      moleculesPresent(siteIndex) = if (useMapBag)
        new MutableMapBag[AbsMolValue[_]]()
      else
        new MutableQueueBag[AbsMolValue[_]]()

      // Assign the RS info on molecule or throw exception on error.
      mol.isBoundToAnotherReactionSite(this) match {
        case Some(otherRS) =>
          throw new ExceptionMoleculeAlreadyBound(s"Molecule $mol cannot be used as input in $this since it is already bound to $otherRS")
        case None ⇒
          val isSelfBlocking = selfBlockingMols.get(mol)
          mol.setReactionSiteInfo(this, siteIndex, valType, pipelined, isSelfBlocking)
      }
    }

    // Add output reactions to molecules that may be bound to other reaction sites later.
    nonStaticReactions.foreach { r =>
      r.info.outputs.foreach(_.molecule.addEmittingReaction(r))
    }

    // Perform static analysis.
    val foundWarnings = findStaticMolWarnings(staticMolDeclared, nonStaticReactions) ++ findGeneralWarnings(nonStaticReactions)

    val contendedReactions = consumingReactions.filter(_.length > 1).flatten.toSet

    val foundErrors = findStaticMolDeclarationErrors(staticReactions) ++
      findStaticMolErrors(staticMolDeclared, nonStaticReactions) ++
      findGeneralErrors(nonStaticReactions) ++
      findShadowingErrors(nonStaticReactions.filter(contendedReactions.contains))

    val staticDiagnostics = WarningsAndErrors(foundWarnings, foundErrors, s"$this")

    // This is necessary to prevent the static reactions from running in case there are already errors.
    if (staticDiagnostics.noErrors) {
      emitStaticMols()

      val staticMolsActuallyEmitted = getMoleculeCountsAfterInitialStaticEmission
      val staticMolsEmissionWarnings = findStaticMolsEmissionWarnings(staticMolDeclared, staticMolsActuallyEmitted)
      val staticMolsEmissionErrors = findStaticMolsEmissionErrors(staticMolDeclared, staticMolsActuallyEmitted)
      val staticMolsDiagnostics = WarningsAndErrors(staticMolsEmissionWarnings, staticMolsEmissionErrors, s"$this")
      staticDiagnostics ++ staticMolsDiagnostics
    } else staticDiagnostics
  }

  private def emitStaticMols() = {
    // Emit static molecules now.
    // This must be done without starting any reactions that might consume these molecules.
    // So, we set the flag `nowEmittingStaticMols`, which will prevent other reactions from starting.
    // Note: mutable variables are OK since this is on the same thread as the call to `site`, so it's guaranteed to be single-threaded!
    nowEmittingStaticMols = true
    staticReactions.foreach { reaction =>
      // We run the body of the static reaction, in order to let it emit the initial static molecules.
      // It is OK that the argument is `null` because static reactions match on the wildcard: { case _ => ... }
      reaction.body.apply(null.asInstanceOf[ReactionBodyInput])
    }
    nowEmittingStaticMols = false
  }

  /** Determine whether the molecule with site-wide index `i` can be pipelined, and return the corresponding input information.
    * A molecule can be pipelined only if its input conditions are completely independent of all other molecules.
    *
    * @param i Site-wide index of a molecule.
    * @return `None` if the molecule is not pipelined. Otherwise return the set of [[InputMoleculeInfo]] values
    *         describing that molecule's conditionals in all reactions consuming that molecule.
    *         The set is empty if the molecule has no conditionals in any of the consuming reactions.
    */
  private def infosIfPipelined(i: Int): Option[Set[InputMoleculeInfo]] = optimize {
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
    */
  private val moleculeAtIndex: Map[Int, MolEmitter] = knownInputMolecules.map { case (mol, (i, _)) ⇒ (i, mol) }(breakOut)

  /** For each site-wide molecule index, this array holds the array of reactions consuming that molecule.
    */
  //  private val consumingReactions: Array[Array[Reaction]] =
  //    Array.tabulate(knownInputMolecules.size)(i ⇒ reactionInfos.keys.filter(_.inputMoleculesSet contains moleculeAtIndex(i)).toArray)
  // Instead of traversing all molecules, traverse all reactions and accumulate results. This is faster.

  private[jc] val consumingReactions: Array[Array[Reaction]] = optimize {
    val table = scala.collection.mutable.Map[MolEmitter, scala.collection.mutable.Set[Reaction]]()
    reactions.foreach { r ⇒
      r.info.inputs.foreach { info ⇒
        table.update(info.molecule, {
          val newSet = table.getOrElse(info.molecule, scala.collection.mutable.Set())
          newSet.add(r)
          newSet
        })
      }
    }
    Array.tabulate(knownInputMolecules.size)(i ⇒ table(moleculeAtIndex(i)).toArray)
  }

  // This must be lazy because it depends on site-wide molecule indices, which are known late.
  // The inner array contains site-wide indices for reaction input molecules; the outer array is also indexed by site-wide molecule indices.
  //  private lazy val relatedMolecules: Array[Array[Int]] = Array.tabulate(knownInputMolecules.size)(i ⇒ consumingReactions(i).flatMap(_.inputMoleculesSet.map(_.index)).distinct)

  /** For each (site-wide) molecule index, the corresponding set of [[InputMoleculeInfo]]s contains only the infos with nontrivial conditions for the molecule value.
    * This is used to assign the pipelined status of a molecules and also to obtain the conditional for that molecule's value.
    */
  private val pipelinedMolecules: Map[Int, Set[InputMoleculeInfo]] = optimize {
    moleculeAtIndex
      .flatMap { case (index, _) ⇒
        infosIfPipelined(index).map(c ⇒ (index, c))
      }
  }

  /** Print warning messages and throw exception if the initialization of this reaction site caused errors.
    *
    * @return Warnings and errors as a [[WarningsAndErrors]] value. If errors were found, throws an exception and returns nothing.
    */
  private[jc] def checkWarningsAndErrors(): WarningsAndErrors = checkWarningsAndErrors(diagnostics)

  private def checkWarningsAndErrors(warningsAndErrors: WarningsAndErrors): WarningsAndErrors = {
    if (warningsAndErrors.warnings.nonEmpty)
      reactionPool.reporter.reactionSiteWarning(id, toString, warningsAndErrors.warnings.mkString("; "))
    if (warningsAndErrors.noErrors)
      warningsAndErrors
    else {
      val errorMessage = warningsAndErrors.errors.mkString("; ")
      reactionPool.reporter.reactionSiteError(id, toString, errorMessage)
      throw new Exception(s"In $this: $errorMessage")
    }
  }

  // This call should be done at the very end of the reaction site constructor because it depends on `pipelinedMolecules`, `consumingReactions`, `knownInputMolecules`,
  // and other values that need to be already computed. This call will also report the elapsed time, measuring the overhead of creating a new reaction site.
  private val diagnostics: WarningsAndErrors = {
    val warningsAndErrors = initializeReactionSite()
    val endTime = System.nanoTime()
    reactionPool.reporter.reactionSiteCreated(id, toString, initTime, endTime)
    warningsAndErrors
  }
}

final case class WarningsAndErrors(warnings: Seq[String], errors: Seq[String], reactionSite: String) {
  def noErrors: Boolean = errors.isEmpty

  def hasErrorsOrWarnings: Boolean = warnings.nonEmpty || errors.nonEmpty

  def ++(other: WarningsAndErrors): WarningsAndErrors =
    copy(warnings = warnings ++ other.warnings, errors = errors ++ other.errors)

  override val toString: String = {
    if (hasErrorsOrWarnings)
      s"In $reactionSite: ${(warnings ++ errors).mkString(". ")}"
    else
      s"In $reactionSite: no warnings or errors"
  }
}

/** Exceptions of this class are thrown on error conditions due to incorrect usage of `Chymyst Core`.
  *
  * @param message Description of the error.
  */
private[jc] sealed class ExceptionInChymyst(message: String) extends Exception(message)

private[jc] final class ExceptionNoReactionSite(message: String) extends ExceptionInChymyst(message)

private[jc] final class ExceptionMoleculeAlreadyBound(message: String) extends ExceptionInChymyst(message)

private[jc] final class ExceptionNoReactionPool(message: String) extends ExceptionInChymyst(message)

private[jc] final class ExceptionEmittingStaticMol(message: String) extends ExceptionInChymyst(message)

private[jc] sealed trait ReactionExitStatus {
  def getMessage: String

  protected final val header: String = ". Reported error: "

  def reactionSucceededOrFailedWithoutRetry: Boolean = true
}

private[jc] case object ReactionExitSuccess extends ReactionExitStatus {
  override val getMessage: String = ""
}

private[jc] final case class ReactionExitFailure(message: String) extends ReactionExitStatus {
  override val getMessage: String = header + message
}

private[jc] final case class ReactionExitRetryFailure(message: String) extends ReactionExitStatus {
  override val getMessage: String = header + message

  override def reactionSucceededOrFailedWithoutRetry: Boolean = false
}
