package code.chymyst.jc

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters.asScalaIteratorConverter

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

  private val (nonSingletonReactions, singletonReactions) = reactions.partition(_.inputMolecules.nonEmpty)

  /** The table of statically declared singleton molecules and their multiplicities.
    * Only non-blocking molecules can be singletons.
    * This list may be incorrect if the singleton reaction code emits molecules conditionally or emits many copies.
    * So, the code (1 to 10).foreach (_ => singleton() ) will put (singleton -> 1) into `singletonsDeclared` but (singleton -> 10) into `singletonsEmitted`.
    */
  private[jc] val singletonsDeclared: Map[Molecule, Int] =
    singletonReactions.map(_.info.outputs)
      .flatMap(_.map(_.molecule).filterNot(_.isBlocking))
      .groupBy(identity)
      .mapValues(_.size)

  /** Complete information about reactions declared in this reaction site.
    * Singleton-declaring reactions are not included here.
    */
  private[jc] val reactionInfos: Map[Reaction, Array[InputMoleculeInfo]] = nonSingletonReactions.map { r => (r, r.info.inputs) }(scala.collection.breakOut)

  // TODO: implement
  //  private val quiescenceCallbacks: mutable.Set[E] = mutable.Set.empty

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

  private val emissionRunnable: Runnable = new Runnable {
    override def run(): Unit = {
      if (newMoleculeQueue.isEmpty) sitePool.drainQueue()
      // Now the queue could be again not empty, so continue.
      newMoleculeQueue.iterator().asScala.foreach { case (mol, molValue) =>
        ???
      }
    }
  }

  //  private[jc] def setQuiescenceCallback(callback: E): Unit = {
  //    quiescenceCallbacks.add(callback)
  //    ()
  //  }
  //
  //  private lazy val possibleReactions: Map[Molecule, Seq[Reaction]] = reactionInfos.toSeq
  //    .flatMap { case (r, ms) => ms.map { info => (info.molecule, r) } }
  //    .groupBy { case (m, r) => m }
  //    .map { case (m, rs) => (m, rs.map(_._2)) }

  // Initially, there are no molecules present.
  private val moleculesPresent: MoleculeBag = new MutableBag[Molecule, AbsMolValue[_]]

  //  private[jc] def emitMulti(moleculesAndValues: Seq[(M[_], Any)]): Unit = {
  // TODO: implement correct semantics
  //    moleculesAndValues.foreach{ case (m, v) => m(v) }
  //  }

  private sealed trait ReactionExitStatus {
    def reactionSucceededOrFailedWithoutRetry: Boolean = true
  }

  private case object ReactionExitSuccess extends ReactionExitStatus

  private case object ReactionExitFailure extends ReactionExitStatus

  private case object ReactionExitRetryFailure extends ReactionExitStatus {
    override def reactionSucceededOrFailedWithoutRetry: Boolean = false
  }

  /** This closure will be run on the reaction thread pool to start a new reaction.
    *
    * @param reaction   Reaction to run.
    * @param usedInputs Molecules (with values) that are consumed by the reaction.
    */
  private def buildReactionClosure(reaction: Reaction, usedInputs: InputMoleculeList): Unit = {
    if (logLevel > 1) println(s"Debug: In $this: reaction {${reaction.info}} started on thread pool $reactionPool with thread id ${Thread.currentThread().getId}")
    val exitStatus: ReactionExitStatus = try {
      // Here we actually apply the reaction body to its input molecules.
      reaction.body.apply(usedInputs)
      ReactionExitSuccess
    } catch {
      // Various exceptions that occurred while running the reaction.
      case e: ExceptionInJoinRun =>
        // Running the reaction body produced an exception that is internal to JoinRun.
        // We should not try to recover from this; it is most either an error on user's part
        // or a bug in JoinRun.
        reportError(s"In $this: Reaction {${reaction.info}} produced an exception that is internal to JoinRun. Input molecules [${moleculeBagToString(usedInputs)}] were not emitted again. Message: ${e.getMessage}")
        // Let's not print it, and let's not throw it again, since it's our internal exception.
        //        e.printStackTrace() // This will be printed asynchronously, out of order with the previous message.
        //        throw e
        ReactionExitFailure

      case e: Exception =>
        // Running the reaction body produced an exception. Note that the exception has killed a thread.
        // We will now re-insert the input molecules (except the blocking ones). Hopefully, no side-effects or output molecules were produced so far.
        val (status, aboutMolecules) = if (reaction.retry) {
          usedInputs.foreach { case (mol, v) => emit(mol, v) }
          (ReactionExitRetryFailure, "were emitted again")
        }
        else (ReactionExitFailure, "were consumed and not emitted again")

        reportError(s"In $this: Reaction {${reaction.info}} produced an exception. Input molecules [${moleculeBagToString(usedInputs)}] $aboutMolecules. Message: ${e.getMessage}")
        //        e.printStackTrace() // This will be printed asynchronously, out of order with the previous message. Let's not print this.
        status
    }

    // Now that the reaction is finished, we inspect the results.

    // For any blocking input molecules that have no reply, put an error message into them and reply with empty
    // value to unblock the threads.

    // Compute error messages here in case we will need them later.
    val blockingMoleculesWithNoReply = usedInputs
      .filter(_._2.reactionSentNoReply)
      .map(_._1).toSeq.toOptionSeq.map(_.map(_.toString).sorted.mkString(", "))

    val messageNoReply = blockingMoleculesWithNoReply.map { s => s"Error: In $this: Reaction {${reaction.info}} with inputs [${moleculeBagToString(usedInputs)}] finished without replying to $s" }

    val blockingMoleculesWithMultipleReply = usedInputs
      .filter(_._2.reactionSentRepeatedReply)
      .map(_._1).toSeq.toOptionSeq.map(_.map(_.toString).sorted.mkString(", "))

    val messageMultipleReply = blockingMoleculesWithMultipleReply map { s => s"Error: In $this: Reaction {${reaction.info}} with inputs [${moleculeBagToString(usedInputs)}] replied to $s more than once" }

    // We will report all errors to each blocking molecule.
    // However, if the reaction failed with retry, we don't yet need to release semaphores and don't need to report errors due to missing reply.
    val errorMessage = Seq(messageNoReply, messageMultipleReply).flatten.mkString("; ")
    val haveErrorsWithBlockingMolecules =
      (blockingMoleculesWithNoReply.nonEmpty && exitStatus.reactionSucceededOrFailedWithoutRetry) || blockingMoleculesWithMultipleReply.nonEmpty

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
  private def currentReactionInfo: Option[ReactionInfo] = {
    Thread.currentThread match {
      case t: ThreadWithInfo => t.reactionInfo
      case _ => None
    }
  }

  private def findReaction(m: Molecule): Option[(Reaction, InputMoleculeList)] = {
    val candidateReactions: Seq[Reaction] = flatten(m.consumingReactions).shuffle // The shuffle will ensure fairness across reactions.
    candidateReactions.toStream
      .flatMap(_.findInputMolecules(moleculesPresent)) // Finding the input molecules may be expensive. We use a stream to avoid doing this for all reactions in advance.
      .headOption // We need only one reaction.
  }

  private def assignSingletonValue[T](m: M[T], molValue: AbsMolValue[T]): Unit =
    m.volatileValueContainer = molValue.getValue

  private def handleSingleton[T](m: M[T], molValue: AbsMolValue[T]): Unit = {
    if (singletonsDeclared.get(m).isEmpty) throw new ExceptionEmittingSingleton(s"In $this: Refusing to emit singleton $m($molValue) not declared in this reaction site")

    // This thread is allowed to emit this singleton only if it is a ThreadWithInfo and the reaction running on this thread has consumed this singleton.
    val reactionInfoOpt = currentReactionInfo
    val isAllowedToEmit = reactionInfoOpt.exists(_.inputMoleculesSet.contains(m))
    if (!isAllowedToEmit) {
      val refusalReason = reactionInfoOpt match {
        case Some(`emptyReactionInfo`) | None => "this thread does not run a chemical reaction"
        case Some(info) => s"this reaction {$info} does not consume it"
      }
      val errorMessage = s"In $this: Refusing to emit singleton $m($molValue) because $refusalReason"
      throw new ExceptionEmittingSingleton(errorMessage)
    }

    // This thread is allowed to emit a singleton; but are there already enough copies of this singleton?
    val oldCount = moleculesPresent.getCount(m)
    val maxCount = singletonsEmitted.getOrElse(m, 0)
    if (oldCount + 1 > maxCount) throw new ExceptionEmittingSingleton(s"In $this: Refusing to emit singleton $m($molValue) having current count $oldCount, max count $maxCount")

    // OK, we can proceed to emit this singleton molecule.
    assignSingletonValue(m, molValue)
  }

  /** Add a new molecule to the bag of molecules at its reaction site.
    * Then decide on which reaction can be started, and schedule that reaction on the reaction pool.
    * Adding a molecule may trigger at most one reaction, due to linearity of input patterns.
    *
    * This method could be scheduled to run on a separate thread.
    *
    * @param m        Molecule to be emitted (can be blocking or non-blocking).
    * @param molValue Wrapper for the molecule's value. (This is either a blocking molecule value wrapper or a non-blocking molecule value wrapper.)
    * @tparam T The type of value carried by the molecule.
    */
  private def buildEmitClosure[T](m: Molecule, molValue: AbsMolValue[T]): Unit = try {
    val foundReactionAndInputs =
      synchronized {
        if (m.isSingleton)
          handleSingleton(m.asInstanceOf[M[T]], molValue)

        moleculesPresent.addToBag(m, molValue)

        if (logLevel > 0) println(s"Debug: $this emitting $m($molValue) on thread pool $sitePool, now have molecules [${moleculeBagToString(moleculesPresent)}]")

        val found = findReaction(m) // This option value will be non-empty if we have a reaction with some input molecules that all have admissible values for that reaction.

        found.foreach {
          case (_, inputsFound) =>
            inputsFound.foreach { case (k, v) => moleculesPresent.removeFromBag(k, v) }
        }
        found
      } // End of synchronized block.

    // We already decided on starting a reaction, so we don't hold the `synchronized` lock on the molecule bag any more.
    foundReactionAndInputs match {
      case Some((reaction, usedInputs)) =>
        // Build a closure out of the reaction, and run that closure on the reaction's thread pool.
        val poolForReaction = reaction.threadPool.getOrElse(reactionPool)
        if (poolForReaction.isInactive)
          throw new ExceptionNoReactionPool(s"In $this: cannot run reaction {${reaction.info}} since reaction pool is not active")
        else if (!Thread.currentThread().isInterrupted)
          if (logLevel > 1) println(s"Debug: In $this: starting reaction {${reaction.info}} on thread pool $poolForReaction while on thread pool $sitePool with inputs [${moleculeBagToString(usedInputs)}]")
        if (logLevel > 2) println(
          if (moleculesPresent.isEmpty)
            s"Debug: In $this: no molecules remaining"
          else
            s"Debug: In $this: remaining molecules [${moleculeBagToString(moleculesPresent)}]"
        )
        // Schedule the reaction now. Provide reaction info to the thread.
        poolForReaction.runClosure(buildReactionClosure(reaction, usedInputs), reaction.info)

      case None =>
        if (logLevel > 2) println(s"Debug: In $this: no reactions started")
    }

  } catch {
    case e: ExceptionInJoinRun => reportError(e.getMessage)
  }

  /** This variable is true only at the initial stage of building the reaction site,
    * when singleton reactions are run (on the same thread as the `site()` call) in order to emit the initial singletons.
    */
  private var emittingSingletonsNow = false

  private[jc] def emit[T](m: Molecule, molValue: AbsMolValue[T]): Unit = {
    if (sitePool.isInactive)
      throw new ExceptionNoSitePool(s"In $this: Cannot emit molecule $m($molValue) because join pool is not active")
    else if (!Thread.currentThread().isInterrupted) {
      if (emittingSingletonsNow) {
        // Emit them on the same thread, and do not start any reactions.
        if (m.isSingleton) {
          moleculesPresent.addToBag(m, molValue)
          assignSingletonValue(m.asInstanceOf[M[T]], molValue)
        } else {
          throw new ExceptionEmittingSingleton(s"In $this: Refusing to emit molecule $m($molValue) as a singleton (must be a non-blocking molecule)")
        }
      } else {
        // Check singleton permissions etc., throw exceptions on errors, set volatileValueContainer already, but do not add anything to moleculesPresent
        newMoleculeQueue.add((m, molValue))
        sitePool.runRunnable(emissionRunnable)
        // emissionRunnable will do most of the job in buildEmitClosure
        //        sitePool.runClosure(buildEmitClosure(m, molValue), currentReactionInfo.getOrElse(emptyReactionInfo))
      }
    }
    ()
  }

  // Remove a blocking molecule if it is present.
  private def removeBlockingMolecule[T, R](bm: BlockingMolecule[T, R], blockingMolValue: BlockingMolValue[T, R]): Unit = {
    moleculesPresent.synchronized {
      moleculesPresent.removeFromBag(bm, blockingMolValue)
      if (logLevel > 0) println(s"Debug: $this removed $bm($blockingMolValue) on thread pool $sitePool, now have molecules [${moleculeBagToString(moleculesPresent)}]")
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
  private def emitAndAwaitReplyInternal[T, R](timeoutOpt: Option[Long], bm: BlockingMolecule[T, R], v: T, replyValueWrapper: AbsReplyValue[T, R]): ReplyStatus = {
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
  private[jc] def emitAndAwaitReply[T, R](bm: BlockingMolecule[T, R], v: T, replyValueWrapper: AbsReplyValue[T, R]): R = {
    // check if we had any errors, and that we have a result value
    emitAndAwaitReplyInternal(timeoutOpt = None, bm, v, replyValueWrapper) match {
      case ErrorNoReply(message) => throw new Exception(message)
      case HaveReply(res) => res.asInstanceOf[R] // Cannot guarantee type safety due to type erasure of `R`.
    }
  }

  // This is a separate method because it has a different return type than [[emitAndAwaitReply]].
  private[jc] def emitAndAwaitReplyWithTimeout[T, R](timeout: Long, bm: BlockingMolecule[T, R], v: T, replyValueWrapper: AbsReplyValue[T, R]):
  Option[R] = {
    // check if we had any errors, and that we have a result value
    emitAndAwaitReplyInternal(timeoutOpt = Some(timeout), bm, v, replyValueWrapper) match {
      case ErrorNoReply(message) => throw new Exception(message)
      case HaveReply(res) =>
        if (replyValueWrapper.isTimedOut)
          None
        else
          Some(res.asInstanceOf[R]) // Cannot guarantee type safety due to type erasure of `R`.
    }
  }

  private def initializeReactionSite(): (Map[Molecule, Int], WarningsAndErrors) = {
    // Set the owner on all input molecules in this reaction site.
    nonSingletonReactions
      .flatMap(_.inputMolecules)
      .toSet // We only need to assign the owner on each distinct input molecule once.
      .foreach { m: Molecule =>
      m.reactionSiteOpt match {
        case Some(owner) => if (owner =!= this) throw new ExceptionMoleculeAlreadyBound(s"Molecule $m cannot be used as input in $this since it is already bound to $owner")
        case None => m.reactionSiteOpt = Some(this)
      }
    }

    // Add output reactions to molecules that may be bound to other reaction sites later.
    nonSingletonReactions.foreach { r =>
      r.info.outputs.foreach(_.molecule.addEmittingReaction(r))
    }

    // Perform static analysis.
    val foundWarnings = findSingletonWarnings(singletonsDeclared, nonSingletonReactions) ++ findStaticWarnings(nonSingletonReactions)

    val foundErrors = findSingletonDeclarationErrors(singletonReactions) ++
      findSingletonErrors(singletonsDeclared, nonSingletonReactions) ++
      findStaticErrors(nonSingletonReactions)

    val staticDiagnostics = WarningsAndErrors(foundWarnings, foundErrors, s"$this")

    staticDiagnostics.checkWarningsAndErrors()

    // Emit singleton molecules (note: this is on the same thread as the call to `site`!).
    // This must be done without starting any reactions.
    emittingSingletonsNow = true
    singletonReactions.foreach { reaction => reaction.body.apply(null.asInstanceOf[InputMoleculeList]) } // It is OK that the argument is `null` because singleton reactions match on the wildcard: { case _ => ... }
    emittingSingletonsNow = false

    val singletonsActuallyEmitted = moleculesPresent.getCountMap

    val singletonEmissionWarnings = findSingletonEmissionWarnings(singletonsDeclared, singletonsActuallyEmitted)
    val singletonEmissionErrors = findSingletonEmissionErrors(singletonsDeclared, singletonsActuallyEmitted)

    val singletonDiagnostics = WarningsAndErrors(singletonEmissionWarnings, singletonEmissionErrors, s"$this")
    val diagnostics = staticDiagnostics ++ singletonDiagnostics

    (singletonsActuallyEmitted, diagnostics)
  }

  // This is run when this ReactionSite is first created.
  private val (singletonsEmitted: Map[Molecule, Int], diagnostics: WarningsAndErrors) = initializeReactionSite()

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


private[jc] sealed class ExceptionInJoinRun(message: String) extends Exception(message)

private[jc] final class ExceptionNoReactionSite(message: String) extends ExceptionInJoinRun(message)

private[jc] final class ExceptionMoleculeAlreadyBound(message: String) extends ExceptionInJoinRun(message)

private[jc] final class ExceptionNoSitePool(message: String) extends ExceptionInJoinRun(message)

private[jc] final class ExceptionEmittingSingleton(message: String) extends ExceptionInJoinRun(message)

private[jc] final class ExceptionNoReactionPool(message: String) extends ExceptionInJoinRun(message)

private[jc] final class ExceptionNoWrapper(message: String) extends ExceptionInJoinRun(message)

private[jc] final class ExceptionWrongInputs(message: String) extends ExceptionInJoinRun(message)

private[jc] final class ExceptionNoSingleton(message: String) extends ExceptionInJoinRun(message)
