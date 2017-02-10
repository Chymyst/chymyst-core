package io.chymyst.jc

import Core._
import StaticAnalysis._

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
    consumingReactions = getConsumingReactions(molecule),
    sameReactionSite = _.id === this.id
  )

  private def getConsumingReactions(m: Molecule): List[Reaction] = reactionInfos.keys.filter(_.inputMoleculesSet contains m).toList

  private val id: Long = getNextId

  private val (nonStaticReactions, staticReactions) = reactions.partition(_.inputMolecules.nonEmpty)

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
    .mapValues(_.size)

  /** Complete information about reactions declared in this reaction site.
    * Static reactions are not included here.
    */
  private val reactionInfos: Map[Reaction, Array[InputMoleculeInfo]] = nonStaticReactions
    .map { r => (r, r.info.inputs) }(scala.collection.breakOut)

  private val knownReactions: Seq[Reaction] = reactionInfos.keys.toSeq

  override val toString: String = s"Site{${knownReactions.map(_.toString).sorted.mkString("; ")}}"

  /** The sha1 hash sum of the entire reaction site, computed from sha1 of each reaction.
    * The sha1 hash of each reaction is computed from the Scala syntax tree of the reaction's source code.
    * The result is implementation-dependent and is guaranteed to be the same only for reaction sites compiled from exactly the same source code with the same version of Scala compiler.
    */
  //  private lazy val sha1 = getSha1String(knownReactions.map(_.info.sha1).sorted.mkString(","))

  private var logLevel = 0

  private def printBag: String = {
    val moleculesPrettyPrinted = if (bags.exists(!_.isEmpty))
      s"Molecules: ${moleculeBagToString(bags)}"
    else "No molecules"

    s"$toString\n$moleculesPrettyPrinted"
  }

  private def decideReactionsForNewMolecule(m: Molecule): Unit = try {
    val foundReactionAndInputs =
      bags.synchronized {

        // This option value will be non-empty if we have a reaction with some input molecules that all have admissible values for that reaction.
        val found: Option[(Reaction, InputMoleculeList)] = findReaction(m)

        found.foreach(_._2.foreach { case (k, v) => removeFromBag(k, v) })
        found
      } // End of synchronized block.

    // We already decided on starting a reaction, so we don't hold the `synchronized` lock on the molecule bag any more.
    foundReactionAndInputs match {
      case Some((reaction, usedInputs)) =>
        // Build a closure out of the reaction, and run that closure on the reaction's thread pool.
        val poolForReaction = reaction.threadPool.getOrElse(reactionPool)
        if (poolForReaction.isInactive)
          throw new ExceptionNoReactionPool(s"In $this: cannot run reaction {${reaction.info}} since reaction pool is not active")
        else if (!Thread.currentThread().isInterrupted) {
          lazy val startingReactionMessage = s"Debug: In $this: starting reaction {${reaction.info}} on thread pool $poolForReaction while on thread pool $sitePool with inputs [${Core.moleculeBagToString(usedInputs)}]"
          if (logLevel > 1) println(startingReactionMessage)
        }
        lazy val moleculesRemainingMessage =
          if (bags.forall(_.isEmpty))
            s"Debug: In $this: no molecules remaining"
          else
            s"Debug: In $this: remaining molecules [${moleculeBagToString(bags)}]"

        if (logLevel > 2) println(moleculesRemainingMessage)
        // Schedule the reaction now. Provide reaction info to the thread.
        scheduleReaction(reaction, usedInputs, poolForReaction)

      case None =>
        if (logLevel > 2) {
          lazy val noReactionsStartedMessage = s"Debug: In $this: no reactions started"
          println(noReactionsStartedMessage)
        }
    }

  } catch {
    case e: ExceptionInChymyst => reportError(e.getMessage)
  }

  private def scheduleReaction(reaction: Reaction, usedInputs: InputMoleculeList, poolForReaction: Pool): Unit =
    poolForReaction.runClosure(runReaction(reaction, usedInputs, poolForReaction: Pool), reaction.newChymystThreadInfo)

  private def emissionRunnable(m: Molecule): Runnable = new Runnable {
    override def run(): Unit = decideReactionsForNewMolecule(m)
  }

  private def reportError(message: String): Unit = {
    if (logLevel > 0) println(message)
    Core.reportError(message)
  }

  // Initially, there are no molecules present.
  //  private val moleculesPresent: MoleculeBag = new MutableBag[Molecule, AbsMolValue[_]]

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
    * @param reaction   Reaction to run.
    * @param usedInputs Molecules (with values) that are consumed by the reaction.
    */
  private def runReaction(reaction: Reaction, usedInputs: InputMoleculeList, poolForReaction: Pool): Unit = {
    lazy val reactionStartMessage = s"Debug: In $this: reaction {${reaction.info}} started on thread pool $reactionPool with thread id ${Thread.currentThread().getId}"

    if (logLevel > 1) println(reactionStartMessage)
    val exitStatus: ReactionExitStatus = try {
      // Here we actually apply the reaction body to its input molecules.
      reaction.body.apply((usedInputs.length - 1, usedInputs))
      // If we are here, we had no exceptions during evaluation of reaction body.
      ReactionExitSuccess
    } catch {
      // Catch various exceptions that occurred while running the reaction body.
      case e: ExceptionInChymyst =>
        // Running the reaction body produced an exception that is internal to `Chymyst Core`.
        // We should not try to recover from this; it is either an error on user's part
        // or a bug in `Chymyst Core`.
        val message = s"In $this: Reaction {${reaction.info}} with inputs [${Core.moleculeBagToString(usedInputs)}] produced an exception that is internal to Chymyst Core. Retry run was not scheduled. Message: ${e.getMessage}"
        reportError(message)
        ReactionExitFailure(message)

      case e: Exception =>
        // Running the reaction body produced an exception. Note that the exception has killed a thread.
        // We will now re-insert the input molecules (except the blocking ones). Hopefully, no side-effects or output molecules were produced so far.
        val (status, retryMessage) =
          if (reaction.retry) {
            scheduleReaction(reaction, usedInputs, poolForReaction)
            (ReactionExitRetryFailure(e.getMessage), " Retry run was scheduled.")
          }
          else (ReactionExitFailure(e.getMessage), " Retry run was not scheduled.")

        val generalExceptionMessage = s"In $this: Reaction {${reaction.info}} with inputs [${Core.moleculeBagToString(usedInputs)}] produced an exception.$retryMessage Message: ${e.getMessage}"

        reportError(generalExceptionMessage)
        status
    }

    // Now that the reaction is finished, we inspect the results.

    // For any blocking input molecules that have no reply, put an error message into them and reply with empty
    // value to unblock the threads.

    // Compute error messages here in case we will need them later.
    val blockingMoleculesWithNoReply = usedInputs
      .filter(_._2.reactionSentNoReply)
      .map(_._1).toSeq.toOptionSeq.map(_.map(_.toString).sorted.mkString(", "))

    // Make this non-lazy to improve coverage.
    val errorMessageFromStatus = exitStatus.getMessage.map(message => s". Reported error: $message").getOrElse("")

    lazy val messageNoReply = blockingMoleculesWithNoReply.map { s =>
      s"Error: In $this: Reaction {${reaction.info}} with inputs [${Core.moleculeBagToString(usedInputs)}] finished without replying to $s$errorMessageFromStatus"
    }

    // We will report all errors to each blocking molecule.
    // However, if the reaction failed with retry, we don't yet need to release semaphores and don't need to report errors due to missing reply.
    lazy val errorMessage = messageNoReply.mkString("; ")

    val haveErrorsWithBlockingMolecules = blockingMoleculesWithNoReply.nonEmpty && exitStatus.reactionSucceededOrFailedWithoutRetry

    // Insert error messages into the reply wrappers and release all semaphores.
    usedInputs.foreach {
      case (_, bm@BlockingMolValue(_, replyValue)) =>
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

  private def findReaction(m: Molecule): Option[(Reaction, InputMoleculeList)] = {
    m.consumingReactions.flatMap(r =>
      r.shuffle // The shuffle will ensure fairness across reactions.
        // We only need to find one reaction whose input molecules are available. For this, we use the special `Core.findAfterMap`.
        .findAfterMap(_.findInputMolecules(m, bags))
    )
  }

  /** Check if the current thread is allowed to emit a static molecule.
    * If so, remove the emitter from the mutable set.
    *
    * @param m A static molecule emitter.
    * @return `()` if the thread is allowed to emit that molecule. Otherwise, an exception is thrown with a refusal reason message.
    */
  private def registerEmittedStaticMolOrThrow(m: Molecule, throwError: String => Unit): Unit = {
    val noChemicalReactionMessage = "because this thread does not run a chemical reaction"
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
      throwError(noChemicalReactionMessage)
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
    * @param m        Molecule emitter.
    * @param molValue Value of the molecule, wrapped in an instance of [[AbsMolValue]]`[T]` class.
    * @tparam T Type of the molecule value.
    */
  private def emit[T](m: Molecule, molValue: AbsMolValue[T]): Unit = {
    if (findUnboundOutputMolecules) {
      lazy val noReactionMessage = s"In $this: As $m($molValue) is emitted, some reactions may emit molecules (${unboundOutputMolecules.map(_.toString).toList.sorted.mkString(", ")}) that are not bound to any reaction site"
      throw new ExceptionNoReactionSite(noReactionMessage)
    }
    else if (sitePool.isInactive) {
      lazy val noPoolMessage = s"In $this: Cannot emit molecule $m($molValue) because site pool is not active"
      throw new ExceptionNoSitePool(noPoolMessage)
    }
    else if (!Thread.currentThread().isInterrupted) {
      if (emittingStaticMolsNow) {
        // Emit them on the same thread, and do not start any reactions.
        if (m.isStatic) {
          addToBag(m, molValue)
          m.asInstanceOf[M[T]].assignStaticMolVolatileValue(molValue)
        } else {
          throw new ExceptionEmittingStaticMol(s"In $this: Refusing to emit molecule $m($molValue) as static (must be a non-blocking molecule)")
        }
      } else {
        if (m.isStatic) {
          // Check permission and throw exceptions on errors, but do not add anything to moleculesPresent and do not yet set the volatile value.
          // If successful, this will modify the thread's copy of `ChymystThreadInfo` to register the fact that we emitted that static molecule.
          registerEmittedStaticMolOrThrow(m, refusalReason =>
            throw new ExceptionEmittingStaticMol(s"In $this: Refusing to emit static molecule $m($molValue) $refusalReason")
          )
          m.asInstanceOf[M[T]].assignStaticMolVolatileValue(molValue)
        }
        // If we are here, we are allowed to emit.
        addToBag(m, molValue)

        lazy val emitMoleculeMessage = s"Debug: $this emitting $m($molValue), now have molecules [${moleculeBagToString(bags)}]"
        if (logLevel > 0) println(emitMoleculeMessage)

        sitePool.runRunnable(emissionRunnable(m))
      }
    }
  }

  /** Compute a map of molecule counts in the soup.
    *
    * @return For each molecule present in the soup, the map shows the number of copies present.
    */
  private def getCountMap: Map[Molecule, Int] =
    bags.indices
      .flatMap(i => if (bags(i).isEmpty)
        None
      else
        Some((moleculeAtIndex(i), bags(i).size))
      )(scala.collection.breakOut)

  private def addToBag(m: Molecule, molValue: AbsMolValue[_]): Unit = bags(m.index).add(molValue)

  private def removeFromBag(m: Molecule, molValue: AbsMolValue[_]): Unit = bags(m.index).remove(molValue)

  private[jc] def moleculeBagToString(bags: Array[MolValueBag[AbsMolValue[_]]]): String =
    Core.moleculeBagToString(bags.indices
      .flatMap(i => if (bags(i).isEmpty)
        None
      else
        Some((moleculeAtIndex(i), bags(i).getCountMap))
      )(scala.collection.breakOut): Map[Molecule, Map[AbsMolValue[_], Int]]
    )

  // Remove a blocking molecule if it is present.
  private def removeBlockingMolecule[T, R](bm: B[T, R], blockingMolValue: BlockingMolValue[T, R]): Unit = {
    bags.synchronized {
      removeFromBag(bm, blockingMolValue)
      lazy val removeBlockingMolMessage = s"Debug: $this removed $bm($blockingMolValue) on thread pool $sitePool, now have molecules [${moleculeBagToString(bags)}]"
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
  private def emitAndAwaitReplyInternal[T, R](timeoutOpt: Option[Long], bm: B[T, R], v: T, replyValueWrapper: AbsReplyValue[T, R]): ReplyStatus = {
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
  private def emitAndAwaitReply[T, R](bm: B[T, R], v: T, replyValueWrapper: AbsReplyValue[T, R]): R = {
    // check if we had any errors, and that we have a result value
    emitAndAwaitReplyInternal(timeoutOpt = None, bm, v, replyValueWrapper) match {
      case ErrorNoReply(message) =>
        throw new Exception(message)
      case HaveReply(res) =>
        res.asInstanceOf[R] // Cannot guarantee type safety due to type erasure of `R`.
    }
  }

  // This is a separate method because it has a different return type than [[emitAndAwaitReply]].
  private def emitAndAwaitReplyWithTimeout[T, R](timeout: Long, bm: B[T, R], v: T, replyValueWrapper: AbsReplyValue[T, R]):
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

  /** This is called once, when the reaction site is first declared using the [[site()]] call.
    * It is called on the thread that calls [[site()]].
    *
    * @return A tuple containing the molecule value bags, and a list of warning and error messages.
    */
  private def initializeReactionSite() = {

    // Set the RS info on all input molecules in this reaction site.
    knownMolecules.foreach { case (mol, (index, valType)) ⇒
      // Assign the value bag.
      val pipelined = pipelinedMolecules contains index
      val simpleType = simpleTypes contains valType
      bags(index) = if (simpleType && !pipelined)
        new MolValueMapBag[AbsMolValue[_]]()
      else
        new MolValueQueueBag[AbsMolValue[_]]()

      // Assign the RS info on molecule or throw exception on error.
      mol.isBoundToAnotherReactionSite(this) match {
        case Some(otherRS) =>
          throw new ExceptionMoleculeAlreadyBound(s"Molecule $mol cannot be used as input in $this since it is already bound to $otherRS")
        case None => mol.setReactionSiteInfo(this, index, valType, pipelinedMolecules contains index)
      }
    }

    // Add output reactions to molecules that may be bound to other reaction sites later.
    nonStaticReactions.foreach { r =>
      r.info.outputs.foreach(_.molecule.addEmittingReaction(r))
    }

    // This set will be examined again when any molecule bound to this site is emitted, so that errors can be signalled as early as possible.
    unboundOutputMolecules = nonStaticReactions.flatMap(_.info.outputs.map(_.molecule)).toSet.filterNot(_.isBound)

    // Perform static analysis.
    val foundWarnings = findStaticMolWarnings(staticMolDeclared, nonStaticReactions) ++ findStaticWarnings(nonStaticReactions)

    val foundErrors = findStaticMolDeclarationErrors(staticReactions) ++
      findStaticMolErrors(staticMolDeclared, nonStaticReactions) ++
      findGeneralErrors(nonStaticReactions)

    val staticDiagnostics = WarningsAndErrors(foundWarnings, foundErrors, s"$this")

    staticDiagnostics.checkWarningsAndErrors()

    // Emit static molecules (note: this is on the same thread as the call to `site`!).
    // This must be done without starting any reactions.
    emittingStaticMolsNow = true
    staticReactions.foreach { reaction =>
      // It is OK that the argument is `null` because static reactions match on the wildcard: { case _ => ... }
      reaction.body.apply(null.asInstanceOf[ReactionBodyInput])
    }
    emittingStaticMolsNow = false

    val staticMolsActuallyEmitted = getCountMap
    val staticMolsEmissionWarnings = findStaticMolsEmissionWarnings(staticMolDeclared, staticMolsActuallyEmitted)
    val staticMolsEmissionErrors = findStaticMolsEmissionErrors(staticMolDeclared, staticMolsActuallyEmitted)

    val staticMolsDiagnostics = WarningsAndErrors(staticMolsEmissionWarnings, staticMolsEmissionErrors, s"$this")
    val diagnostics = staticDiagnostics ++ staticMolsDiagnostics

    diagnostics
  }

  private val knownMolecules: Map[Molecule, (Int, Symbol)] = nonStaticReactions
    .flatMap(_.inputMolecules)
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

  private def isPipelined(m: Molecule): Boolean = getConsumingReactions(m)
    .flatFoldLeft[(Set[String], Boolean, Boolean)]((Set(), false, true)) {
    case (acc, r) ⇒
      val (prevConds, prevHaveOtherInputs, isFirstReaction) = acc
      val haveOtherInputs = r.info.inputs.exists(_.molecule =!= m)
      val inputsForThisMolecule = r.info.inputs.filter(_.molecule === m)

      // There should be no cross-molecule conditions / guards involving this molecule; otherwise, it is fatal.
      if (inputsForThisMolecule.map(_.index).toSet subsetOf r.info.independentInputMolecules) {
        // Get the conditions for this molecule. There should be no conditions when the molecule is repeated, and at most one otherwise.
        val thisConds = inputsForThisMolecule.filterNot(_.flag.isIrrefutable).map(_.sha1).toSet
        // Check whether this molecule is nonlinear in input (if so, there should be no conditions).
        //          val isNonlinear = inputsForThisMolecule.length >= 2
        // If we have no previous other inputs and no current other inputs, we can concatenate the conditions and we are done.
        val newHaveOtherInputs = haveOtherInputs || prevHaveOtherInputs
        if (newHaveOtherInputs) {
          // If we have other inputs either now, or previously, or both,
          // we do not fail only if the previous condition is exactly the same as the current one, or if this is the first condition we are considering.
          if (isFirstReaction || (prevConds subsetOf thisConds))
            Some((prevConds, newHaveOtherInputs, false))
          else
            None
        } else {
          Some((prevConds ++ thisConds, newHaveOtherInputs, false))
        }
      } else {
        None
      }
  }.nonEmpty

  private val pipelinedMolecules: Set[Int] = knownMolecules.filterKeys(isPipelined).map(_._2._1).toSet

  private val moleculeAtIndex: Map[Int, Molecule] = knownMolecules.map { case (m, (i, _)) => (i, m) }(scala.collection.breakOut)

  private val bags: MoleculeBagArray = new Array(knownMolecules.size)

  /** Print warnings messages and throw exception if the initialization of this reaction site caused errors.
    *
    * @return Warnings and errors as a [[WarningsAndErrors]] value. If errors were found, throws an exception and returns nothing.
    */
  private[jc] def checkWarningsAndErrors(): WarningsAndErrors = diagnostics.checkWarningsAndErrors()

  // This should be done at the very end, after all other values are computed.
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
  * Specific values of [[ReactionSiteWrapper]] are created by [[ReactionSite.makeWrapper()]]
  * and assigned to molecule emitters by [[Molecule.setReactionSiteInfo()]]).
  */
private[jc] final class ReactionSiteWrapper[T, R](
                                                   override val toString: String,
                                                   val logSoup: () => String,
                                                   val setLogLevel: Int => Unit,
                                                   val staticMolsDeclared: List[Molecule],
                                                   val emit: (Molecule, AbsMolValue[T]) => Unit,
                                                   val emitAndAwaitReply: (B[T, R], T, AbsReplyValue[T, R]) => R,
                                                   val emitAndAwaitReplyWithTimeout: (Long, B[T, R], T, AbsReplyValue[T, R]) => Option[R],
                                                   val consumingReactions: List[Reaction],
                                                   val sameReactionSite: ReactionSite => Boolean
                                                 )

private[jc] object ReactionSiteWrapper {
  def noReactionSite[T, R](m: Molecule): ReactionSiteWrapper[T, R] = {
    def exception: Nothing = throw new ExceptionNoReactionSite(s"Molecule $m is not bound to any reaction site")

    new ReactionSiteWrapper(
      toString = "",
      logSoup = () => exception,
      setLogLevel = _ => exception,
      staticMolsDeclared = Nil,
      emit = (_: Molecule, _: AbsMolValue[T]) => exception,
      emitAndAwaitReply = (_: B[T, R], _: T, _: AbsReplyValue[T, R]) => exception,
      emitAndAwaitReplyWithTimeout = (_: Long, _: B[T, R], _: T, _: AbsReplyValue[T, R]) => exception,
      consumingReactions = Nil,
      sameReactionSite = _ => exception
    )
  }
}
