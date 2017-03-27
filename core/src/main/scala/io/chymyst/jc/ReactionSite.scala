package io.chymyst.jc

import Core._
import StaticAnalysis._

import scala.annotation.tailrec

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
    staticMolDeclared.keys.toList,
    emit = (mol, molValue) => emit[T](mol, molValue),
    emitAndAwaitReply = (mol, molValue, replyValue) => emitAndAwaitReply[T, R](mol, molValue, replyValue),
    emitAndAwaitReplyWithTimeout = (timeout, mol, molValue, replyValue) => emitAndAwaitReplyWithTimeout[T, R](timeout, mol, molValue, replyValue),
    consumingReactions = consumingReactions(molecule.index),
    sameReactionSite = _.id === this.id
  )

  private def getConsumingReactions(m: Molecule): Array[Reaction] = reactionInfos.keys.filter(_.inputMoleculesSet contains m).toArray

  private val id: Long = getNextId

  private val (nonStaticReactions, staticReactions) = reactions.toArray.partition(_.inputMoleculesSortedAlphabetically.nonEmpty)

  private var unboundOutputMolecules: Set[Molecule] = _

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
    .map { r => (r, r.info.inputs) }(scala.collection.breakOut)

  override val toString: String = s"Site{${nonStaticReactions.map(_.toString).sorted.mkString("; ")}}"

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

  @tailrec
  private def decideReactionsForNewMolecule(mol: Molecule): Unit = {
    // TODO: optimize: pre-fetch all counts for related molecules and compare them with required counts before calling findInputMolecules; also precompute all related molecules in ReactionSite
    if (logLevel > 3) println(s"Debug: In $this: deciding reactions for molecule $mol, present molecules [${moleculeBagToString(moleculesPresent)}]")
    val foundReactionAndInputs =
      moleculesPresent.synchronized {
        // The optimization consists of fetching the largest count that we might need for any reaction; then takeAny(count).size does the right thing
        val relatedMoleculeCounts = Array.tabulate[Int](moleculesPresent.length)(i ⇒ moleculesPresent(i).takeAny(maxRequiredMoleculeCount(i)).size)
        // This option value will be non-empty if we have a reaction with some input molecules that all have admissible values for that reaction.
        val found: Option[(Reaction, InputMoleculeList)] = findReaction(mol, relatedMoleculeCounts)
        // If we have found a reaction that can be run, remove its input molecule values from their bags.
        found.foreach { case (thisReaction, thisInputList) ⇒
          thisInputList.indices.foreach { i ⇒
            val molValue = thisInputList(i)
            val mol = thisReaction.info.inputs(i).molecule
            // This error (molecule value was not in bag) indicates a bug in this code, which should already manifest itself in failing tests!
            if (!removeFromBag(mol, molValue)) reportError(s"Error: In $this: Internal error: Failed to remove molecule $mol($molValue) from its bag; molecule index ${mol.index}, bag ${moleculesPresent(mol.index)}")
          }
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
          decideReactionsForNewMolecule(mol) // Need to try running another reaction with the same molecule, if possible.
        }
      case None =>
        if (logLevel > 2) {
          println(noReactionsStartedMessage)
        }
    }

  }

  val noMoleculesRemainingMessage = s"Debug: In $this: no molecules remaining"

  val noReactionsStartedMessage = s"Debug: In $this: no reactions started"

  private def scheduleReaction(reaction: Reaction, usedInputs: InputMoleculeList, poolForReaction: Pool): Unit =
    poolForReaction.runClosure(runReaction(reaction, usedInputs, poolForReaction: Pool), reaction.newChymystThreadInfo)

  private def emissionRunnable(mol: Molecule): Runnable = new Runnable {
    override def run(): Unit = {
      val reactions = consumingReactions(mol.index)
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
        // We should not try to recover from this; it is either an error on user's part
        // or a bug in `Chymyst Core`.
        val message = s"In $this: Reaction {${thisReaction.info}} with inputs [$reactionInputs] produced an exception that is internal to Chymyst Core. Retry run was not scheduled. Message: ${e.getMessage}"
        reportError(message)
        ReactionExitFailure(message)

      case e: Exception =>
        // Running the reaction body produced an exception. Note that the exception has killed a thread.
        // We will now re-insert the input molecules (except the blocking ones). Hopefully, no side-effects or output molecules were produced so far.
        val (status, retryMessage) =
          if (thisReaction.retry) {
            scheduleReaction(thisReaction, usedInputs, poolForReaction)
            (ReactionExitRetryFailure(e.getMessage), " Retry run was scheduled.")
          }
          else (ReactionExitFailure(e.getMessage), " Retry run was not scheduled.")

        val generalExceptionMessage = s"In $this: Reaction {${thisReaction.info}} with inputs [$reactionInputs] produced an exception.$retryMessage Message: ${e.getMessage}"

        reportError(generalExceptionMessage)
        status
    }

    // Now that the reaction is finished, we inspect the results.

    // For any blocking input molecules that have no reply, put an error message into them and reply with empty
    // value to unblock the threads.

    // Compute error messages here in case we will need them later.
    val blockingMoleculesWithNoReply = usedInputs.zipWithIndex
      .filter(_._1.reactionSentNoReply)
      .map{ case (_, i) ⇒ thisReaction.info.inputs(i).molecule}
      .toSeq.toOptionSeq
      .map(_.map(_.toString).sorted.mkString(", "))

    // Make this non-lazy to improve coverage.
    val errorMessageFromStatus = exitStatus.getMessage.map(message => s". Reported error: $message").getOrElse("")

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

  private def reactionHasAChanceOfStarting(molCounts: Array[Int])(r: Reaction): Boolean = {
    // Check that all input molecules for this reaction have counts that are not less than required by this reaction.
    // This is just a preliminary check, since molecule values could fail some conditions.
    r.moleculeIndexRequiredCounts.forall {
      case (mIndex, count) ⇒ molCounts(mIndex) >= count
    } &&
      // Check that the static guard holds.
      // If the static guard fails, we don't need to search for any input molecule values.
      r.info.guardPresence.staticGuardHolds()
  }

  private def findReaction(mol: Molecule, molCounts: Array[Int]): Option[(Reaction, InputMoleculeList)] = {
    consumingReactions(mol.index)
      .filter(reactionHasAChanceOfStarting(molCounts))
      // We only need to find one reaction whose input molecules are available. For this, we use the special `Core.findAfterMap`.
      .findAfterMap(_.findInputMolecules(moleculesPresent))
  }

  /** Check if the current thread is allowed to emit a static molecule.
    * If so, remove the emitter from the mutable set.
    *
    * @param m A static molecule emitter.
    * @return `()` if the thread is allowed to emit that molecule. Otherwise, an exception is thrown with a refusal reason message.
    */
  private def allowEmittedStaticMolOrThrow(m: Molecule, throwError: String => Unit): Unit = {
    currentReactionInfo.foreach { info =>
      if (!info.maybeEmit(m)) {
        val refusalReason =
          if (!info.couldEmit(m))
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
  private var emittingStaticMolsNow = false

  private def findUnboundOutputMolecules: Boolean = unboundOutputMolecules.nonEmpty && {
    val stillUnbound = unboundOutputMolecules.filterNot(_.isBound)
    unboundOutputMolecules = stillUnbound
    stillUnbound.nonEmpty
  }

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
      lazy val noReactionMessage = s"In $this: As $mol($molValue) is emitted, some reactions may emit molecules (${unboundOutputMolecules.map(_.toString).toList.sorted.mkString(", ")}) that are not bound to any reaction site"
      throw new ExceptionNoReactionSite(noReactionMessage)
    }
    else if (sitePool.isInactive) {
      lazy val noPoolMessage = s"In $this: Cannot emit molecule $mol($molValue) because site pool is not active"
      throw new ExceptionNoSitePool(noPoolMessage)
    }
    else if (!Thread.currentThread().isInterrupted) {
      if (emittingStaticMolsNow) {
        // Emit them on the same thread, and do not start any reactions.
        if (mol.isStatic) {
          addToBag(mol, molValue)
          mol.asInstanceOf[M[T]].assignStaticMolVolatileValue(molValue)
        } else {
          throw new ExceptionEmittingStaticMol(s"In $this: Refusing to emit molecule $mol($molValue) as static (must be a non-blocking molecule)")
        }
      } else {
        // For pipelined molecules, check whether their value satisfies at least one of the conditions (if any conditions are present).
        // For non-pipelined molecules, `admitsValue` will be `true`.
        val admitsValue = pipelinedMolecules.get(mol.index).forall(infos ⇒ infos.isEmpty || infos.exists(_.admitsValue(molValue)))
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

          sitePool.runRunnable(emissionRunnable(mol))
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
  private def getMoleculeCountsAfterFirstEmission: Map[Molecule, Int] =
    moleculesPresent.indices
      .flatMap(i => if (moleculesPresent(i).isEmpty)
        None
      else
        Some((moleculeAtIndex(i), moleculesPresent(i).size))
      )(scala.collection.breakOut)

  private def addToBag(mol: Molecule, molValue: AbsMolValue[_]): Unit = moleculesPresent(mol.index).add(molValue)

  private def removeFromBag(mol: Molecule, molValue: AbsMolValue[_]): Boolean = moleculesPresent(mol.index).remove(molValue)

  private[jc] def moleculeBagToString(bags: Array[MutableBag[AbsMolValue[_]]]): String =
    Core.moleculeBagToString(bags.indices
      .flatMap(i => if (bags(i).isEmpty)
        None
      else
        Some((moleculeAtIndex(i), bags(i).getCountMap))
      )(scala.collection.breakOut): Map[Molecule, Map[AbsMolValue[_], Int]]
    )

  // Remove a blocking molecule if it is present.
  private def removeBlockingMolecule[T, R](bm: B[T, R], blockingMolValue: BlockingMolValue[T, R]): Unit = {
    moleculesPresent.synchronized {
      removeFromBag(bm, blockingMolValue)
      lazy val removeBlockingMolMessage = s"Debug: $this removed $bm($blockingMolValue) on thread pool $sitePool, now have molecules [${moleculeBagToString(moleculesPresent)}]"
      if (logLevel > 0) println(removeBlockingMolMessage)
    }
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

    // This set will be examined again when any molecule bound to this site is emitted, so that errors can be signalled as early as possible.
    unboundOutputMolecules = nonStaticReactions.flatMap(_.info.outputs.map(_.molecule)).toSet.filterNot(_.isBound)

    // Precompute max required molecule counts in reactions.
    maxRequiredMoleculeCount.indices.foreach { i ⇒
      consumingReactions(i).foreach(_.inputMoleculesSortedAlphabetically.foreach(mol ⇒ maxRequiredMoleculeCount(mol.index) += 1))
    }

    // Perform static analysis.
    val foundWarnings = findStaticMolWarnings(staticMolDeclared, nonStaticReactions) ++ findStaticWarnings(nonStaticReactions)

    val foundErrors = findStaticMolDeclarationErrors(staticReactions) ++
      findStaticMolErrors(staticMolDeclared, nonStaticReactions) ++
      findGeneralErrors(nonStaticReactions)

    val staticDiagnostics = WarningsAndErrors(foundWarnings, foundErrors, s"$this")

    staticDiagnostics.checkWarningsAndErrors()

    // Emit static molecules now.
    // This must be done without starting any reactions that might consume these molecules.
    // So, we set the flag `emittingStaticMolsNow`, which will prevent other reactions from starting.
    // Note: mutable variables are OK since this is on the same thread as the call to `site`, so it's guaranteed to be single-threaded!
    emittingStaticMolsNow = true
    staticReactions.foreach { reaction =>
      // It is OK that the argument is `null` because static reactions match on the wildcard: { case _ => ... }
      reaction.body.apply(null.asInstanceOf[ReactionBodyInput])
    }
    emittingStaticMolsNow = false

    val staticMolsActuallyEmitted = getMoleculeCountsAfterFirstEmission
    val staticMolsEmissionWarnings = findStaticMolsEmissionWarnings(staticMolDeclared, staticMolsActuallyEmitted)
    val staticMolsEmissionErrors = findStaticMolsEmissionErrors(staticMolDeclared, staticMolsActuallyEmitted)

    val staticMolsDiagnostics = WarningsAndErrors(staticMolsEmissionWarnings, staticMolsEmissionErrors, s"$this")
    val diagnostics = staticDiagnostics ++ staticMolsDiagnostics

    diagnostics
  }

  private val knownMolecules: Map[Molecule, (Int, Symbol)] = {
    nonStaticReactions
      .flatMap(_.inputMoleculesSortedAlphabetically)
      .distinct // We only need to assign the owner on each distinct input molecule once.
      .sortBy(_.name)
      .zipWithIndex
      .map { case (mol, index) ⇒

        val valType = nonStaticReactions
          .map(_.info.inputs)
          .flatMap(_.find(_.molecule === mol))
          .headOption
          .map(_.valType)
          .getOrElse("<unknown>".toScalaSymbol)

        (mol, (index, valType))
      }(scala.collection.breakOut)
  }

  /** The element of this array at index i is the maximum number of copies of the molecule with site-wide index i that can be consumed by any reaction.
    * This array will be computed at reaction site initialization time.
    */
  private val maxRequiredMoleculeCount: Array[Int] = Array.fill(knownMolecules.size)(0)

  private def infosIfPipelined(i: Int): Option[Set[InputMoleculeInfo]] = {
    consumingReactions(i)
      .flatFoldLeft[(Set[InputMoleculeInfo], Boolean, Boolean)]((Set(), false, true)) {
      case (acc, r) ⇒
        val (prevConds, prevHaveOtherInputs, isFirstReaction) = acc
        val haveOtherInputs = r.info.inputs.exists(_.molecule =!= moleculeAtIndex(i))
        val inputsForThisMolecule = r.info.inputs.filter(_.molecule === moleculeAtIndex(i))

        // There should be no cross-molecule conditions / guards involving this molecule or any of its reaction partners; otherwise, it cannot be pipelined.
        if (inputsForThisMolecule.map(_.index).toSet subsetOf r.info.independentInputMolecules) {
          // Get the conditions for this molecule. There should be no conditions when the molecule is repeated, and at most one otherwise.
          val thisConds = inputsForThisMolecule.filterNot(_.flag.isIrrefutable).toSet
          // Check whether this molecule is nonlinear in input (if so, there should be no conditions).
          //          val isNonlinear = inputsForThisMolecule.length >= 2
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

  private val moleculeAtIndex: Map[Int, Molecule] = knownMolecules.map { case (mol, (i, _)) ⇒ (i, mol) }(scala.collection.breakOut)

  /** For each site-wide molecule index, this array holds the array of reactions consuming that molecule.
    *
    */
  private val consumingReactions: Array[Array[Reaction]] = Array.tabulate(knownMolecules.size)(i ⇒ getConsumingReactions(moleculeAtIndex(i)))

  // This must be lazy because it depends on site-wide molecule indices, which are known late.
  // The inner array contains site-wide indices for reaction input molecules; the outer array is also indexed by site-wide molecule indices.
  //  private lazy val relatedMolecules: Array[Array[Int]] = Array.tabulate(knownMolecules.size)(i ⇒ consumingReactions(i).flatMap(_.inputMoleculesSet.map(_.index)).distinct)

  /** For each (site-wide) molecule index, the corresponding set of [[InputMoleculeInfo]]s contains only the infos with nontrivial conditions for the molecule value.
    *
    */
  private val pipelinedMolecules: Map[Int, Set[InputMoleculeInfo]] =
    moleculeAtIndex.flatMap {
      case (index, _) ⇒ infosIfPipelined(index).map(c ⇒ (index, c))
    }

  private val moleculesPresent: MoleculeBagArray = new Array(knownMolecules.size)

  /** Print warnings messages and throw exception if the initialization of this reaction site caused errors.
    *
    * @return Warnings and errors as a [[WarningsAndErrors]] value. If errors were found, throws an exception and returns nothing.
    */
  private[jc] def checkWarningsAndErrors(): WarningsAndErrors = diagnostics.checkWarningsAndErrors()

  // This should be done at the very end, after all other values are computed, because it depends on `pipelinedMolecules`, `consumingReactions`, `knownMolecules`, and other computed values.
  private val diagnostics: WarningsAndErrors = initializeReactionSite()
}

final case class WarningsAndErrors(warnings: Seq[String], errors: Seq[String], reactionSite: String) {
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
  val staticMolsDeclared: List[Molecule],
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
      staticMolsDeclared = List[Molecule](),
      emit = (_: Molecule, _: AbsMolValue[T]) => exception,
      emitAndAwaitReply = (_: B[T, R], _: T, _: AbsReplyEmitter[T, R]) => exception,
      emitAndAwaitReplyWithTimeout = (_: Long, _: B[T, R], _: T, _: AbsReplyEmitter[T, R]) => exception,
      consumingReactions = Array[Reaction](),
      sameReactionSite = _ => exception
    )
  }
}
