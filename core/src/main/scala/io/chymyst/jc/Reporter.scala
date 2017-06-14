package io.chymyst.jc

import java.time.LocalDateTime
import java.util.concurrent.LinkedBlockingQueue

import io.chymyst.jc.Core._

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.concurrent.duration.Duration

abstract class Reporter(logTransport: LogTransport) {
  @inline def log(message: String): Unit = {
    logTransport.log(message): @inline
  }

  def emitted(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String, moleculesPresent: ⇒ String): Unit = ()

  def removed(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String, moleculesPresent: ⇒ String): Unit = ()

  def replyReceived(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String): Unit = ()

  def replyTimedOut(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, timeout: Duration): Unit = ()

  def reactionSiteCreated(rsId: ReactionSiteId, rsString: ReactionSiteString): Unit = ()

  def schedulerStep(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, moleculesPresent: ⇒ String): Unit = ()

  def reactionScheduled(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, reaction: ReactionString, inputs: ⇒ String, remainingMols: ⇒ String): Unit = ()

  def noReactionScheduled(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString): Unit = ()

  def reactionStarted(rsId: ReactionSiteId, rsString: ReactionSiteString, reaction: ReactionString, inputs: ⇒ String): Unit = ()

  def reactionFinished(rsId: ReactionSiteId, rsString: ReactionSiteString, reaction: ReactionString, inputs: ⇒ String, status: ReactionExitStatus): Unit = ()

  def errorReport(rsId: ReactionSiteId, rsString: ReactionSiteString, message: String, printToConsole: Boolean = false): Unit = ()

  def reportDeadlock(poolName: String, maxPoolSize: Int, blockingCalls: Int, reactionInfo: ReactionString): Unit = ()

  /** Access the reporter's global message log. This is used by reaction sites to report errors, metrics, and debugging messages at run time.
    *
    * @return An [[Iterable]] representing the sequence of all messages in the message log.
    */
  def globalErrorLog: Iterable[String] = errorLog.iterator().asScala.toIterable

  /** Clear the global error log used by all reaction sites to report runtime errors.
    *
    */
  def clearGlobalErrorLog(): Unit = errorLog.clear()

  private val errorLog = new LinkedBlockingQueue[String]()
}

/** This [[Reporter]] never prints any messages at all.
  *
  */
object NoopSilentReporter extends Reporter(NoopLogTransport)

/** This [[Reporter]] prints no messages except errors, which are logged to console.
  *
  */
sealed class ReportErrors(logTransport: LogTransport) extends Reporter(logTransport) {
  override def errorReport(rsId: ReactionSiteId, rsString: ReactionSiteString, message: String, enable: Boolean = false): Unit = {
    if (enable) this.log(s"${LocalDateTime.now}: Error: In $rsString: $message")
  }

  override def reportDeadlock(poolName: String, maxPoolSize: Int, blockingCalls: Int, reactionInfo: ReactionString): Unit = {
    this.log(s"Error: deadlock occurred in pool $poolName ($maxPoolSize threads) due to $blockingCalls concurrent blocking calls, while running reaction {$reactionInfo}")
  }
}

sealed trait LogTransport {
  def log(message: String): Unit
}

object ConsoleLogTransport extends LogTransport {
  override def log(message: String): Unit = println(message)
}

object NoopLogTransport extends LogTransport {
  override def log(message: String): Unit = ()
}
