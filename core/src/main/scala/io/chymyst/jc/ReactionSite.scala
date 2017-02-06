package io.chymyst.jc

import java.util.concurrent.ConcurrentLinkedQueue

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
    consumingReactions = reactionInfos.keys.filter(_.inputMoleculesSet contains molecule).toList,
    sameReactionSite = _ === this
  )

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

  private lazy val knownReactions: Seq[Reaction] = reactionInfos.keys.toSeq

  override lazy val toString: String = s"Site{${knownReactions.map(_.toString).sorted.mkString("; ")}}"

  /** The sha1 hash sum of the entire reaction site, computed from sha1 of each reaction.
    * The sha1 hash of each reaction is computed from the Scala syntax tree of the reaction's source code.
    * The result is implementation-dependent and is guaranteed to be the same only for reaction sites compiled from exactly the same source code with the same version of Scala compiler.
    */
  //  private lazy val sha1 = getSha1String(knownReactions.map(_.info.sha1).sorted.mkString(","))

  private[jc] var logLevel = 0

  private[jc] def printBag: String = {
    val moleculesPrettyPrinted = if (moleculesPresent.size > 0) s"Molecules: ${moleculeBagToString(moleculesPresent)}" else "No molecules"

    s"${this.toString}\n$moleculesPrettyPrinted"
  }

  private val newMoleculeQueue: ConcurrentLinkedQueue[(Molecule, AbsMolValue[_])] = new ConcurrentLinkedQueue()

  private def decideReactionsForNewMolecule(m: Molecule, molValue: AbsMolValue[_]): Unit = try {
    val foundReactionAndInputs =
      moleculesPresent.synchronized {
        moleculesPresent.addToBag(m, molValue)
        lazy val emitMoleculeMessage = s"Debug: $this emitting $m($molValue) on thread pool $sitePool, now have molecules [${moleculeBagToString(moleculesPresent)}]"
        if (logLevel > 0) println(emitMoleculeMessage)

        // This option value will be non-empty if we have a reaction with some input molecules that all have admissible values for that reaction, and that consume `m(molValue)`.
        val found: Option[(Reaction, InputMoleculeList)] = findReaction(m)

        found.foreach(_._2.foreach { case (k, v) => moleculesPresent.removeFromBag(k, v) })
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
          lazy val startingReactionMessage = s"Debug: In $this: starting reaction {${reaction.info}} on thread pool $poolForReaction while on thread pool $sitePool with inputs [${moleculeBagToString(usedInputs)}]"
          if (logLevel > 1) println(startingReactionMessage)
        }
        lazy val moleculesRemainingMessage =
          if (moleculesPresent.isEmpty)
            s"Debug: In $this: no molecules remaining"
          else
            s"Debug: In $this: remaining molecules [${moleculeBagToString(moleculesPresent)}]"

        if (logLevel > 2) println(moleculesRemainingMessage)
        // Schedule the reaction now. Provide reaction info to the thread.
        poolForReaction.runClosure(buildReactionClosure(reaction, usedInputs), reaction.newChymystThreadInfo)

      case None =>
        if (logLevel > 2) {
          lazy val noReactionsStartedMessage = s"Debug: In $this: no reactions started"
          println(noReactionsStartedMessage)
        }
    }

  } catch {
    case e: ExceptionInChymyst => reportError(e.getMessage)
  }

  @tailrec
  private def dequeueNewMolecules(): Unit = {
    newMoleculeQueue.poll() match {
      case null =>
        ()
      case (m, molValue) =>
        decideReactionsForNewMolecule(m, molValue)
        dequeueNewMolecules() // Keep dequeueing while poll() returns non-null elements.
    }
  }

  private val emissionRunnable: Runnable = new Runnable {
    override def run(): Unit = dequeueNewMolecules()
  }

  private def reportError(message: String): Unit = {
    if (logLevel > 0) println(message)
    Core.reportError(message)
  }

  // Initially, there are no molecules present.
  private val moleculesPresent: MoleculeBag = new MutableBag[Molecule, AbsMolValue[_]]

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
  private def buildReactionClosure(reaction: Reaction, usedInputs: InputMoleculeList): Unit = {
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
        lazy val message = s"In $this: Reaction {${reaction.info}} produced an exception that is internal to Chymyst Core. Input molecules [${moleculeBagToString(usedInputs)}] were not emitted again. Message: ${e.getMessage}"
        reportError(message)
        ReactionExitFailure(message)

      case e: Exception =>
        // Running the reaction body produced an exception. Note that the exception has killed a thread.
        // We will now re-insert the input molecules (except the blocking ones). Hopefully, no side-effects or output molecules were produced so far.
        val (status, aboutMolecules) =
          if (reaction.retry) {
            usedInputs.foreach { case (mol, v) => emit(mol, v) }
            (ReactionExitRetryFailure(e.getMessage), "were emitted again")
          }
          else (ReactionExitFailure(e.getMessage), "were consumed and not emitted again")

        lazy val generalExceptionMessage = s"In $this: Reaction {${reaction.info}} produced an exception. Input molecules [${moleculeBagToString(usedInputs)}] $aboutMolecules. Message: ${e.getMessage}"

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

    // Make this non-lazy to improve coverage?
    val errorMessageFromStatus = exitStatus.getMessage.map(message => s". Reported error: $message").getOrElse("")

    lazy val messageNoReply = blockingMoleculesWithNoReply.map { s =>
      s"Error: In $this: Reaction {${reaction.info}} with inputs [${moleculeBagToString(usedInputs)}] finished without replying to $s$errorMessageFromStatus"
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
        .findAfterMap(_.findInputMolecules(m, moleculesPresent))
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

  def findUnboundOutputMolecules: Boolean = unboundOutputMolecules.nonEmpty && {
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
  private[jc] def emit[T](m: Molecule, molValue: AbsMolValue[T]): Unit = {
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
          moleculesPresent.addToBag(m, molValue)
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
        newMoleculeQueue.add((m, molValue))
        sitePool.runRunnable(emissionRunnable)
      }
    }
  }

  // Remove a blocking molecule if it is present.
  private def removeBlockingMolecule[T, R](bm: B[T, R], blockingMolValue: BlockingMolValue[T, R]): Unit = {
    moleculesPresent.synchronized {
      moleculesPresent.removeFromBag(bm, blockingMolValue)
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
  private def emitAndAwaitReplyInternal[T, R](timeoutOpt: Option[Long], bm: B[T, R], v: T, replyValueWrapper: AbsReplyValue[T, R]): ReplyStatus = {
    val blockingMolValue = BlockingMolValue(v, replyValueWrapper)
    emit(bm, blockingMolValue)
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
  private[jc] def emitAndAwaitReply[T, R](bm: B[T, R], v: T, replyValueWrapper: AbsReplyValue[T, R]): R = {
    // check if we had any errors, and that we have a result value
    emitAndAwaitReplyInternal(timeoutOpt = None, bm, v, replyValueWrapper) match {
      case ErrorNoReply(message) =>
        throw new Exception(message)
      case HaveReply(res) =>
        res.asInstanceOf[R] // Cannot guarantee type safety due to type erasure of `R`.
    }
  }

  // This is a separate method because it has a different return type than [[emitAndAwaitReply]].
  private[jc] def emitAndAwaitReplyWithTimeout[T, R](timeout: Long, bm: B[T, R], v: T, replyValueWrapper: AbsReplyValue[T, R]):
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

  private def initializeReactionSite(): (Map[Molecule, Int], WarningsAndErrors) = {
    // Set the owner on all input molecules in this reaction site.
    nonStaticReactions
      .flatMap(_.inputMolecules)
      .toSet // We only need to assign the owner on each distinct input molecule once.
      .foreach { m: Molecule =>
      m.isBoundToAnother(this) match {
        case Some(otherRS) =>
          throw new ExceptionMoleculeAlreadyBound(s"Molecule $m cannot be used as input in $this since it is already bound to $otherRS")
        case None => m.setReactionSite(this)
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

    val staticMolsActuallyEmitted = moleculesPresent.getCountMap

    val staticMolsEmissionWarnings = findStaticMolsEmissionWarnings(staticMolDeclared, staticMolsActuallyEmitted)
    val staticMolsEmissionErrors = findStaticMolsEmissionErrors(staticMolDeclared, staticMolsActuallyEmitted)

    val staticMolsDiagnostics = WarningsAndErrors(staticMolsEmissionWarnings, staticMolsEmissionErrors, s"$this")
    val diagnostics = staticDiagnostics ++ staticMolsDiagnostics

    (staticMolsActuallyEmitted, diagnostics) // Not sure if we still want `staticMolsActuallyEmitted` stored in the RS class.
  }

  // This is run when this ReactionSite is first created.
  private val diagnostics: WarningsAndErrors = initializeReactionSite()._2

  /** Print warnings messages and throw exception if the initialization of this reaction site caused errors.
    *
    * @return Warnings and errors as a [[WarningsAndErrors]] value. If errors were found, throws an exception and returns nothing.
    */
  def checkWarningsAndErrors(): WarningsAndErrors = diagnostics.checkWarningsAndErrors()
}

final case class WarningsAndErrors(warnings: Seq[String], errors: Seq[String], reactionSite: String) {
  def checkWarningsAndErrors(): WarningsAndErrors = {
    if (warnings.nonEmpty) println(s"In $reactionSite: ${warnings.mkString("; ")}")
    if (errors.nonEmpty) throw new Exception(s"In $reactionSite: ${errors.mkString("; ")}")
    this
  }

  def hasErrorsOrWarnings: Boolean = warnings.nonEmpty || errors.nonEmpty

  def ++(other: WarningsAndErrors): WarningsAndErrors =
    WarningsAndErrors(warnings ++ other.warnings, errors ++ other.errors, reactionSite)
}


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
  * and assigned to molecule emitters by [[Molecule.setReactionSite()]]).
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
