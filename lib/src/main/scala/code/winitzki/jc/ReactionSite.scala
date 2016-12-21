package code.winitzki.jc

import code.winitzki.jc.JoinRun._
import code.winitzki.jc.JoinRunUtils._

import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap}

import collection.mutable


/** Represents the reaction site, which holds one or more reaction definitions (chemical laws).
  * At run time, the reaction site maintains a bag of currently available input molecules and runs reactions.
  * The user will never see any instances of this class.
  *
  * @param reactions List of reactions as defined by the user.
  * @param reactionPool The thread pool on which reactions will be scheduled.
  * @param sitePool The thread pool on which the reaction site will decide reactions and manage the molecule bag.
  */
private final class ReactionSite(reactions: Seq[Reaction], reactionPool: Pool, sitePool: Pool) {

  private val (nonSingletonReactions, singletonReactions) = reactions.partition(_.inputMolecules.nonEmpty)

  /** The table of statically declared singleton molecules and their multiplicities.
    * Only non-blocking molecules can be singletons.
    * This list may be incorrect if the singleton reaction code emits molecules conditionally.
    * So, at the moment (1 to 10).foreach (_ => singleton() ) will not recognize that there are 10 singletons emitted.
    */
  private val singletonsDeclared: Map[Molecule, Int] =
    singletonReactions.flatMap(_.info.outputs)
      .flatMap(_.map(_.molecule).filterNot(_.isBlocking))
      .groupBy(identity)
      .mapValues(_.size)

  /** The table of singleton molecules actually emitted when singleton reactions are first run.
    *
    */

  /** For each declared singleton molecule, store the value it carried when it was last emitted.
    *
    */
  private val singletonValues: ConcurrentMap[Molecule, AbsMolValue[_]] = new ConcurrentHashMap()

  /** Complete information about reactions declared in this reaction site.
    * Singleton-declaring reactions are not included here.
    */
  private[jc] val reactionInfos: Map[Reaction, List[InputMoleculeInfo]] = nonSingletonReactions.map { r => (r, r.info.inputs) }.toMap

  // TODO: implement
  private val quiescenceCallbacks: mutable.Set[M[Unit]] = mutable.Set.empty

  private lazy val knownReactions: Seq[Reaction] = reactionInfos.keys.toSeq

  override lazy val toString: String = s"Site{${knownReactions.map(_.toString).sorted.mkString("; ")}}"

  /** The sha1 hash sum of the entire reaction site, computed from sha1 of each reaction.
    * The sha1 hash of each reaction is computed from the Scala syntax tree of the reaction's source code.
    * The result is implementation-dependent and is guaranteed to be the same only for reaction sites compiled from exactly the same source code with the same version of Scala compiler.
    */
  private lazy val sha1 = getSha1(knownReactions.map(_.info.sha1).sorted.mkString(","))

  private[jc] var logLevel = 0

  private[jc] def printBag: String = {
    val moleculesPrettyPrinted = if (moleculesPresent.size > 0) s"Molecules: ${moleculeBagToString(moleculesPresent)}" else "No molecules"

    s"${this.toString}\n$moleculesPrettyPrinted"
  }

  private[jc] def setQuiescenceCallback(callback: M[Unit]): Unit = {
    quiescenceCallbacks.add(callback)
    ()
  }

  private lazy val possibleReactions: Map[Molecule, Seq[Reaction]] = reactionInfos.toSeq
    .flatMap { case (r, ms) => ms.map { info => (info.molecule, r) } }
    .groupBy { case (m, r) => m }
    .map { case (m, rs) => (m, rs.map(_._2)) }

  // Initially, there are no molecules present.
  private val moleculesPresent: MoleculeBag = new MutableBag[Molecule, AbsMolValue[_]]

  private[jc] def emitMulti(moleculesAndValues: Seq[(M[_], Any)]): Unit = {
    // TODO: implement correct semantics
//    moleculesAndValues.foreach{ case (m, v) => m(v) }
  }

  private sealed trait ReactionExitStatus
  private case object ReactionExitSuccess extends ReactionExitStatus
  private case object ReactionExitFailure extends ReactionExitStatus
  private case object ReactionExitRetryFailure extends ReactionExitStatus

  /** This closure will be run on the reaction thread pool to start a new reaction.
    *
    * @param reaction Reaction to run.
    * @param usedInputs Molecules (with values) that are consumed by the reaction.
    */
  private def buildReactionClosure(reaction: Reaction, usedInputs: LinearMoleculeBag): Unit = {
    if (logLevel > 1) println(s"Debug: In $this: reaction {$reaction} started on thread pool $reactionPool with thread id ${Thread.currentThread().getId}")
    val exitStatus : ReactionExitStatus = try {
      // Here we actually apply the reaction body to its input molecules.
      reaction.body.apply(UnapplyRun(usedInputs))
      ReactionExitSuccess
    } catch {
      // Various exceptions that occurred while running the reaction.
      case e: ExceptionInJoinRun =>
        // Running the reaction body produced an exception that is internal to JoinRun.
        // We should not try to recover from this; it is most either an error on user's part
        // or a bug in JoinRun.
        reportError(s"In $this: Reaction {$reaction} produced an exception that is internal to JoinRun. Input molecules ${moleculeBagToString(usedInputs)} were not emitted again. Message: ${e.getMessage}")
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

        reportError(s"In $this: Reaction {$reaction} produced an exception. Input molecules ${moleculeBagToString(usedInputs)} $aboutMolecules. Message: ${e.getMessage}")
        //        e.printStackTrace() // This will be printed asynchronously, out of order with the previous message. Let's not print this.
        status
    }

    // Now that the reaction is finished, we inspect the results.

    // For any blocking input molecules that have no reply, put an error message into them and reply with empty
    // value to unblock the threads.

    // Compute error messages here in case we will need them later.
    val blockingMoleculesWithNoReply = nonemptyOpt(usedInputs
      .filter { case (_, BlockingMolValue(_, replyValue)) => replyValue.result.isEmpty && !replyValue.replyTimeout; case _ => false }
      .keys.toSeq).map(_.map(_.toString).sorted.mkString(", "))

    val messageNoReply = blockingMoleculesWithNoReply map { s => s"Error: In $this: Reaction {$reaction} finished without replying to $s" }

    val blockingMoleculesWithMultipleReply = nonemptyOpt(usedInputs
      .filter { case (_, BlockingMolValue(_, replyValue)) => replyValue.replyRepeated; case _ => false }
      .keys.toSeq).map(_.map(_.toString).sorted.mkString(", "))

    val messageMultipleReply = blockingMoleculesWithMultipleReply map { s => s"Error: In $this: Reaction {$reaction} replied to $s more than once" }

    // We will report all errors to each blocking molecule.
    // However, if the reaction failed with retry, we don't yet need to release semaphores and don't need to report errors due to missing reply.
    val notFailedWithRetry = exitStatus != ReactionExitRetryFailure
    val errorMessage = Seq(messageNoReply, messageMultipleReply).flatten.mkString("; ")
    val haveErrorsWithBlockingMolecules =
      (blockingMoleculesWithNoReply.nonEmpty && notFailedWithRetry)|| blockingMoleculesWithMultipleReply.nonEmpty

    // Insert error messages into the reply wrappers and release all semaphores.
    usedInputs.foreach {
      case (_, BlockingMolValue(_, replyValue)) =>
        if (haveErrorsWithBlockingMolecules) {
          replyValue.errorMessage = Some(errorMessage)
        }
        if (notFailedWithRetry) replyValue.releaseSemaphore()

      case _ => ()
    }

    if (haveErrorsWithBlockingMolecules) reportError(errorMessage)

  }

  /** Determine whether the current thread is running a reaction, and if so, fetch the reaction info.
    *
    * @return {{{None}}} if the current thread is not running a reaction.
    */
  private def currentReactionInfo: Option[ReactionInfo] = {
    Thread.currentThread match {
      case t: ThreadWithInfo => t.reactionInfo
      case _ => None
    }
  }

  /** Add a new molecule to the bag of molecules at its reaction site.
    * Then decide on which reaction can be started, and schedule that reaction on the reaction pool.
    * Adding a molecule may trigger at most one reaction, due to linearity of input patterns.
    *
    * This method could be scheduled to run on a separate thread.
    *
    * @param m Molecule to be emitted (can be blocking or non-blocking).
    * @param molValue Wrapper for the molecule's value. (This is either a blocking molecule value wrapper or a non-blocking molecule value wrapper.)
    * @tparam T The type of value carried by the molecule.
    */
  private def buildEmitClosure[T](m: Molecule, molValue: AbsMolValue[T]): Unit = try {
    val (reactionOpt: Option[Reaction], usedInputs: LinearMoleculeBag) =
      synchronized {
        if (m.isSingleton) {
          if (singletonsDeclared.get(m).isEmpty) throw new ExceptionEmittingSingleton(s"In $this: Refusing to emit singleton $m($molValue) not declared in this reaction site")

          // This thread is allowed to emit this singleton only if it is a ThreadWithInfo and the reaction running on this thread has consumed this singleton.
          val reactionInfoOpt = currentReactionInfo
          val isAllowedToEmit = reactionInfoOpt.exists(_.inputs.map(_.molecule).contains(m))
          if (!isAllowedToEmit) {
            val refusalReason = reactionInfoOpt match {
              case Some(info) => s"this reaction {$info} does not consume it"
              case None => "this thread does not run a chemical reaction"
            }
            val errorMessage = s"In $this: Refusing to emit singleton $m($molValue) because $refusalReason"
            throw new ExceptionEmittingSingleton(errorMessage)
          }

          // This thread is allowed to emit a singleton; but are there already enough copies of this singleton?
          val oldCount = moleculesPresent.getCount(m)
          val maxCount = singletonsEmitted.getOrElse(m, 0)
          if (oldCount + 1 > maxCount) throw new ExceptionEmittingSingleton(s"In $this: Refusing to emit singleton $m($molValue) having current count $oldCount, max count $maxCount")

          // OK, we can proceed to emit this singleton molecule.
          singletonValues.put(m, molValue)
        }
        moleculesPresent.addToBag(m, molValue)
        if (logLevel > 0) println(s"Debug: $this emitting $m($molValue) on thread pool $sitePool, now have molecules ${moleculeBagToString(moleculesPresent)}")
        val usedInputs: MutableLinearMoleculeBag = mutable.Map.empty
        val reaction = possibleReactions.get(m)
          .flatMap(_.shuffle.find(r => {
            usedInputs.clear()
            r.body.isDefinedAt(UnapplyRunCheck(moleculesPresent, usedInputs))
          }))
        reaction.foreach(_ => moleculesPresent.removeFromBag(usedInputs))
        (reaction, usedInputs.toMap)
      } // End of synchronized block.

    // We already decided on starting a reaction, so we don't hold the `synchronized` lock on the molecule bag any more.
    reactionOpt match {
      case Some(reaction) =>
        // A basic check that we are using our mutable structures safely. We should never see this error.
        if (!reaction.inputMolecules.toSet.equals(usedInputs.keySet)) {
          val message = s"Internal error: In $this: attempt to start reaction {$reaction} with incorrect inputs ${moleculeBagToString(usedInputs)}"
          throw new ExceptionWrongInputs(message)
        }
        // Build a closure out of the reaction, and run that closure on the reaction's thread pool.
        val poolForReaction = reaction.threadPool.getOrElse(reactionPool)
        if (poolForReaction.isInactive)
          throw new ExceptionNoReactionPool(s"In $this: cannot run reaction $reaction since reaction pool is not active")
        else if (!Thread.currentThread().isInterrupted)
          if (logLevel > 1) println(s"Debug: In $this: starting reaction {$reaction} on thread pool $poolForReaction while on thread pool $sitePool with inputs ${moleculeBagToString(usedInputs)}")
        if (logLevel > 2) println(
          if (moleculesPresent.size == 0)
            s"Debug: In $this: no molecules remaining"
          else
            s"Debug: In $this: remaining molecules ${moleculeBagToString(moleculesPresent)}"
        )
          // Schedule the reaction now. Provide reaction info to the thread.
          poolForReaction.runClosure(buildReactionClosure(reaction, usedInputs), reaction.info)

      case None =>
        if (logLevel > 2) println(s"Debug: In $this: no reactions started")
        ()
    }

  } catch {
    case e: ExceptionInJoinRun => reportError(e.getMessage)
  }

  /** This variable is true only at the initial stage of building the reaction site,
    * when singleton reactions are run in order to emit the initial singletons.
    */
  private var emittingSingletons = false

  private[jc] def emit[T](m: Molecule, molValue: AbsMolValue[T]): Unit = {
    if (sitePool.isInactive)
      throw new ExceptionNoSitePool(s"In $this: Cannot emit molecule $m($molValue) because join pool is not active")
    else if (!Thread.currentThread().isInterrupted) {
      if (emittingSingletons) {
        // Emit them on the same thread, and do not start any reactions.
        if (m.isSingleton) {
          moleculesPresent.addToBag(m, molValue)
          singletonValues.put(m, molValue)
        } else {
          throw new ExceptionEmittingSingleton(s"In $this: Refusing to emit molecule $m($molValue) as a singleton (must be a non-blocking molecule)")
        }
      }
      else
        sitePool.runClosure(buildEmitClosure(m, molValue), currentReactionInfo.getOrElse(emptyReactionInfo))
    }
    ()
  }

  // Remove a blocking molecule if it is present.
  private def removeBlockingMolecule[T,R](m: B[T,R], blockingMolValue: BlockingMolValue[T,R], hadTimeout: Boolean): Unit = {
    moleculesPresent.synchronized {
      moleculesPresent.removeFromBag(m, blockingMolValue)
      if (logLevel > 0) println(s"Debug: $this removed $m($blockingMolValue) on thread pool $sitePool, now have molecules ${moleculeBagToString(moleculesPresent)}")
    }
    blockingMolValue.synchronized {
      blockingMolValue.replyValue.replyTimeout = hadTimeout
    }
  }

  private def emitAndReplyInternal[T,R](timeoutOpt: Option[Long], m: B[T,R], v: T, replyValueWrapper: ReplyValue[T,R]): Boolean = {
    val blockingMolValue = BlockingMolValue(v, replyValueWrapper)
    emit(m, blockingMolValue)
    val success =
      BlockingIdle {
        replyValueWrapper.acquireSemaphore(timeoutNanos = timeoutOpt)
      }
    replyValueWrapper.deleteSemaphore()
    // We might have timed out, in which case we need to forcibly remove the blocking molecule from the soup.
    removeBlockingMolecule(m, blockingMolValue, !success)

    success
  }

  // Adding a blocking molecule may trigger at most one reaction and must return a value of type R.
  // We must make this a blocking call, so we acquire a semaphore (with or without timeout).
  private[jc] def emitAndReply[T,R](m: B[T,R], v: T, replyValueWrapper: ReplyValue[T,R]): R = {
    emitAndReplyInternal(timeoutOpt = None, m, v, replyValueWrapper)
    // check if we had any errors, and that we have a result value
    replyValueWrapper.errorMessage match {
      case Some(message) => throw new Exception(message)
      case None => replyValueWrapper.result.getOrElse(
        throw new ExceptionEmptyReply(s"Internal error: In $this: $m received an empty reply without an error message"
        )
      )
    }
  }

  // This is a separate method because it has a different return type than emitAndReply.
  private[jc] def emitAndReplyWithTimeout[T,R](timeout: Long, m: B[T,R], v: T, replyValueWrapper: ReplyValue[T,R]):
  Option[R] = {
    val haveReply = emitAndReplyInternal(timeoutOpt = Some(timeout), m, v, replyValueWrapper)
    // check if we had any errors, and that we have a result value
    replyValueWrapper.errorMessage match {
      case Some(message) => throw new Exception(message)
      case None => if (haveReply) Some(replyValueWrapper.result.getOrElse(
        throw new ExceptionEmptyReply(s"Internal error: In $this: $m received an empty reply without an error message"))
      )
      else None
    }
  }

  private[jc] def hasVolatileValue[T](m: M[T]): Boolean =
    m.isSingleton && singletonValues.containsKey(m)

  private[jc] def getVolatileValue[T](m: M[T]): T = {
    if (m.isSingleton) {
      if (singletonValues.containsKey(m)) {
        singletonValues.get(m).asInstanceOf[AbsMolValue[T]].getValue
      } else throw new Exception(s"Internal error: In $this: The volatile reader for singleton ($m) is not yet ready")
    }
    else
      throw new ExceptionNoSingleton(s"In $this: volatile reader requested for non-singleton ($m)")
  }

  private def initializeJoinDef(): (Map[Molecule, Int], WarningsAndErrors) = {

    // Set the owner on all input molecules in this reaction site.
    nonSingletonReactions
      .flatMap(_.inputMolecules)
      .toSet // We only need to assign the owner on each distinct input molecule once.
      .foreach { m: Molecule =>
      m.reactionSiteOpt match {
        case Some(owner) => throw new ExceptionMoleculeAlreadyBound(s"Molecule $m cannot be used as input since it is already bound to $owner")
        case None => m.reactionSiteOpt = Some(this)
      }
    }

    // Add output reactions to molecules that may be bound to other reaction sites later.
    nonSingletonReactions
      .foreach { r =>
        r.info.outputs.foreach {
          _.foreach { info => info.molecule.emittingReactionsSet += r }
        }
      }

    // Mark the outputs of singleton reactions as singleton molecules.
    singletonReactions.foreach {
      reaction =>
        reaction.info.outputs.foreach(_.foreach {
          case OutputMoleculeInfo(m: M[_], _) => m.isSingletonBoolean = true;
          case _ =>
        })
    }

    // Perform static analysis.
    val foundWarnings = StaticAnalysis.findSingletonWarnings(singletonsDeclared, nonSingletonReactions) ++ StaticAnalysis.findStaticWarnings(nonSingletonReactions)

    val foundErrors = StaticAnalysis.findSingletonDeclarationErrors(singletonReactions) ++
      StaticAnalysis.findSingletonErrors(singletonsDeclared, nonSingletonReactions) ++
      StaticAnalysis.findStaticErrors(nonSingletonReactions)

    val staticDiagnostics = WarningsAndErrors(foundWarnings, foundErrors, s"$this")

    staticDiagnostics.checkWarningsAndErrors()

    // Emit singleton molecules (note: this is on the same thread as the call to `site`!).
    // This must be done without starting any reactions.
    // It is OK that the argument is `null` because singleton reactions match on the wildcard: { case _ => ... }
    emittingSingletons = true
    singletonReactions.foreach { reaction => reaction.body.apply(null.asInstanceOf[UnapplyArg]) }
    emittingSingletons = false

    val singletonsActuallyEmitted = moleculesPresent.getCountMap

    val singletonEmissionWarnings = StaticAnalysis.findSingletonEmissionWarnings(singletonsDeclared, singletonsActuallyEmitted)
    val singletonEmissionErrors = StaticAnalysis.findSingletonEmissionErrors(singletonsDeclared, singletonsActuallyEmitted)

    val singletonDiagnostics = WarningsAndErrors(singletonEmissionWarnings, singletonEmissionErrors, s"$this")
    val diagnostics = staticDiagnostics ++ singletonDiagnostics

    diagnostics.checkWarningsAndErrors()

    (singletonsActuallyEmitted, diagnostics)
  }

  // This is run when this ReactionSite is first created.
  val (singletonsEmitted, diagnostics) = initializeJoinDef()
}

case class WarningsAndErrors(warnings: Seq[String], errors: Seq[String], joinDef: String) {
  def checkWarningsAndErrors(): Unit = {
    if (warnings.nonEmpty) println(s"In $joinDef: ${warnings.mkString("; ")}")
    if (errors.nonEmpty) throw new Exception(s"In $joinDef: ${errors.mkString("; ")}")
  }

  def hasErrorsOrWarnings: Boolean = warnings.nonEmpty || errors.nonEmpty

  def ++(other: WarningsAndErrors): WarningsAndErrors =
    WarningsAndErrors(warnings ++ other.warnings, errors ++ other.errors, joinDef)
}
