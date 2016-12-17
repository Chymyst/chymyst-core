package code.winitzki.jc

import code.winitzki.jc.JoinRun._
import code.winitzki.jc.JoinRunUtils._

import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap}

import collection.mutable


/** Represents the join definition, which holds one or more reaction definitions.
  * At run time, the join definition maintains a bag of currently available molecules
  * and runs reactions.
  * The user will never see any instances of this class.
  *
  * @param reactions List of reactions as defined by the user.
  * @param reactionPool The thread pool on which reactions will be scheduled.
  * @param joinPool The thread pool on which the join definition will decide reactions and manage the molecule bag.
  */
private final class JoinDefinition(reactions: Seq[Reaction], reactionPool: Pool, joinPool: Pool) {

  private val (nonSingletonReactions, singletonReactions) = reactions.partition(_.inputMolecules.nonEmpty)

  /** The list of singleton molecules and their multiplicities.
    * Only non-blocking molecules can be singletons.
    * This list may be incorrect if the singleton reaction code injects molecules conditionally.
    */
  private val singletonsDeclared: Map[Molecule, Int] =
    singletonReactions.flatMap(_.info.outputs)
      .flatMap(_.map(_.molecule).filterNot(_.isBlocking))
      .groupBy(identity)
      .mapValues(_.size)

  private val singletonValues: ConcurrentMap[Molecule, AbsMolValue[_]] = new ConcurrentHashMap()

  /** Complete information about reactions declared in this join definition.
    * Singleton-declaring reactions are not included here.
    */
  private[jc] val reactionInfos: Map[Reaction, List[InputMoleculeInfo]] = nonSingletonReactions.map { r => (r, r.info.inputs) }.toMap

  // TODO: implement
  private val quiescenceCallbacks: mutable.Set[M[Unit]] = mutable.Set.empty

  private lazy val knownReactions: Seq[Reaction] = reactionInfos.keys.toSeq

  private lazy val stringForm = s"Join{${knownReactions.map(_.toString).sorted.mkString("; ")}}"

  override def toString: String = stringForm

  /** The sha1 hash sum of the entire join definition, computed from sha1 of each reaction.
    * The sha1 hash of each reaction is computed from the Scala syntax tree of the reaction's source code.
    * The result is implementation-dependent and is guaranteed to be the same only for join definitions compiled from exactly the same source code with the same version of Scala compiler.
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
  private[jc] def inject[T](m: Molecule, molValue: AbsMolValue[T]): Unit = {
    if (joinPool.isInactive) throw new ExceptionNoJoinPool(s"In $this: Cannot inject molecule $m since join pool is not active")
    else if (!Thread.currentThread().isInterrupted) joinPool.runClosure ({
      val (reaction, usedInputs: LinearMoleculeBag) =
        synchronized {
          if (m.isSingleton) {
            if (singletonsDeclared.get(m).isEmpty) throw new ExceptionInjectingSingleton(s"In $this: Refusing to inject singleton $m($molValue) not declared in this join definition")
            val oldCount = moleculesPresent.getCount(m)
            val maxCount = singletonsDeclared.getOrElse(m, 0)
            if (oldCount + 1 > maxCount) throw new ExceptionInjectingSingleton(s"In $this: Refusing to inject singleton $m($molValue) having current count $oldCount, max count $maxCount")
          }
          moleculesPresent.addToBag(m, molValue)
          singletonValues.put(m, molValue)
          if (logLevel > 0) println(s"Debug: $this injecting $m($molValue) on thread pool $joinPool, now have molecules ${moleculeBagToString(moleculesPresent)}")
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
                println(s"In $this: Reaction {$r} produced an exception that is internal to JoinRun. Input molecules ${moleculeBagToString(usedInputs)} were not injected again. Message: ${e.getMessage}")
                // let's not print it, and let's not throw it again
//                e.printStackTrace() // This will be printed asynchronously, out of order with the previous message.
//                throw e

              case e: Exception =>
                // Running the reaction body produced an exception. Note that the exception has killed a thread.
                // We will now re-insert the input molecules. Hopefully, no side-effects or output molecules were produced so far.
                val aboutMolecules = if (r.retry) {
                  usedInputs.foreach {
                    case (mol : M[_], v) => inject(mol, v)
                    case (mol: B[_,_], v) => () // Do not re-inject blocking molecules - the reply action will be still usable when the reaction re-runs.
                  }
                  "were injected again"
                }
                else "were consumed and not injected again"

                println(s"In $this: Reaction {$r} produced an exception. Input molecules ${moleculeBagToString(usedInputs)} $aboutMolecules. Message: ${e.getMessage}")
                e.printStackTrace() // This will be printed asynchronously, out of order with the previous message.
            }
            // For any blocking input molecules that have no reply, put an error message into them and reply with empty
            // value to unblock the threads.

            def nonemptyOpt[S](s: Seq[S]): Option[Seq[S]] = if (s.isEmpty) None else Some(s)

            // Compute error messages here in case we will need them later.
            val blockingMoleculesWithNoReply = nonemptyOpt(usedInputs
              .filter { case (_, BlockingMolValue(_, replyValue)) => replyValue.result.isEmpty && !replyValue.replyTimeout; case _ => false }
              .keys.toSeq).map(_.map(_.toString).sorted.mkString(", "))

            val messageNoReply = blockingMoleculesWithNoReply map { s => s"Error: In $this: Reaction {$r} finished without replying to $s" }

            val blockingMoleculesWithMultipleReply = nonemptyOpt(usedInputs
              .filter { case (_, BlockingMolValue(_, replyValue)) => replyValue.replyRepeated; case _ => false }
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

    }, name = Some(s"[Injecting $m($molValue) in $this]"))
  }

  private def removeBlockingMolecule[T,R](m: B[T,R], blockingMolValue: BlockingMolValue[T,R]): Unit = synchronized {
    moleculesPresent.removeFromBag(m, blockingMolValue)
    if (logLevel > 0) println(s"Debug: $this removed $m($blockingMolValue) on thread pool $joinPool, now have molecules ${moleculeBagToString(moleculesPresent)}")
    blockingMolValue.replyValue.replyTimeout = true
  }

  private def injectAndReplyInternal[T,R](timeoutOpt: Option[Long], m: B[T,R], v: T, replyValueWrapper: ReplyValue[T,R]): Boolean = {
    val blockingMolValue = BlockingMolValue(v, replyValueWrapper)
    inject(m, blockingMolValue)
    val success =
      try {
        // not sure we need this.
        BlockingIdle {
          replyValueWrapper.acquireSemaphore(timeoutNanos = timeoutOpt)
        }
      }
      catch {
        case e: InterruptedException => e.printStackTrace(); false
        case _: Exception => false
      }
      finally {
        replyValueWrapper.deleteSemaphore() // make sure it's gone
      }

    // If we are here, we might need to forcibly remove the blocking molecule from the soup.
    removeBlockingMolecule(m, blockingMolValue)

    success
  }

  // Adding a blocking molecule may trigger at most one reaction and must return a value of type R.
  // We must make this a blocking call, so we acquire a semaphore (with timeout).
  private[jc] def injectAndReply[T,R](m: B[T,R], v: T, replyValueWrapper: ReplyValue[T,R]): R = {
    injectAndReplyInternal(timeoutOpt = None, m, v, replyValueWrapper)
    // check if we had any errors, and that we have a result value
    replyValueWrapper.errorMessage match {
      case Some(message) => throw new Exception(message)
      case None => replyValueWrapper.result.getOrElse(
        throw new ExceptionEmptyReply(s"Internal error: In $this: $m received an empty reply without an error message"
        )
      )
    }
  }

  private[jc] def injectAndReplyWithTimeout[T,R](timeout: Long, m: B[T,R], v: T, replyValueWrapper: ReplyValue[T,R]):
  Option[R] = {
    val haveReply = injectAndReplyInternal(timeoutOpt = Some(timeout), m, v, replyValueWrapper)
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
      val errorReaderNotReady = new Exception(s"The volatile reader for singleton ($m) is not yet ready")
      if (singletonValues.containsKey(m)) {
        singletonValues.get(m).asInstanceOf[AbsMolValue[T]].getValue
      } else throw errorReaderNotReady
    }
    else
      throw new ExceptionNoSingleton(s"In $this: volatile reader requested for non-singleton ($m)")
  }

  def diagnostics: WarningsAndErrors = diagnosticsValue

  private var diagnosticsValue: WarningsAndErrors = WarningsAndErrors(Nil, Nil, "")

  private def initializeJoinDef() = {

    // Set the owner on all input molecules in this join definition.
    nonSingletonReactions
      .flatMap(_.inputMolecules)
      .toSet // We only need to assign the owner on each distinct input molecule once.
      .foreach { m: Molecule =>
      m.joinDef match {
        case Some(owner) => throw new ExceptionMoleculeAlreadyBound(s"Molecule $m cannot be used as input since it is already bound to $owner")
        case None => m.joinDef = Some(this)
      }
    }

    // Add output reactions to molecules that may be bound to other join definitions later.
    nonSingletonReactions
      .foreach { r =>
        r.info.outputs.foreach {
          _.foreach { info => info.molecule.injectingReactionsSet += r }
        }
      }

    // Detect singleton molecules and inject them.
    singletonReactions.foreach {
      reaction =>
        reaction.info.outputs.foreach(_.foreach{ case OutputMoleculeInfo(m: M[_], _) => m.isSingletonBoolean = true; case _ =>  })
    }

    // Perform static analysis.
    val foundWarnings = StaticAnalysis.findSingletonWarnings(singletonsDeclared, nonSingletonReactions) ++ StaticAnalysis.findStaticWarnings(nonSingletonReactions)

    val foundErrors = StaticAnalysis.findSingletonErrors(singletonsDeclared, nonSingletonReactions) ++ StaticAnalysis.findStaticErrors(nonSingletonReactions)

    diagnosticsValue = WarningsAndErrors(foundWarnings, foundErrors, s"$this")

    diagnosticsValue.checkWarningsAndErrors()

    // Inject singleton molecules.
    singletonReactions.foreach { reaction => reaction.body.apply(null) }

  }

  // This is run when this Join Definition is first created.
  initializeJoinDef()
}

case class WarningsAndErrors(warnings: Seq[String], errors: Seq[String], joinDef: String) {
  def checkWarningsAndErrors(): Unit = {
    if (warnings.nonEmpty) println(s"In $joinDef: ${warnings.mkString("; ")}")
    if (errors.nonEmpty) throw new Exception(s"In $joinDef: ${errors.mkString("; ")}")
  }

  def hasErrorsOrWarnings: Boolean = warnings.nonEmpty || errors.nonEmpty
}
