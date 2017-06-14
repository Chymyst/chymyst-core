package io.chymyst.jc

import java.time.LocalDateTime
import java.util.concurrent.LinkedBlockingQueue

import io.chymyst.jc.Core._

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.concurrent.duration.Duration

abstract class Reporter(logTransport: LogTransport) extends ReportEvents {
  @inline def log(message: String): Unit = {
    logTransport.log(message): @inline
  }
}

trait ReportEvents {
  def log(message: String): Unit

  def emitted(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String, moleculesPresent: ⇒ String): Unit = ()

  def removed(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String, moleculesPresent: ⇒ String): Unit = ()

  def replyReceived(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String): Unit = ()

  def replyTimedOut(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, timeout: Duration): Unit = ()

  def reactionSiteCreated(rsId: ReactionSiteId, rsString: ReactionSiteString, startNs: Long, endNs: Long): Unit = ()

  def reactionSiteError(rsId: ReactionSiteId, rsString: ReactionSiteString, message: ⇒ String): Unit = ()

  def reactionSiteWarning(rsId: ReactionSiteId, rsString: ReactionSiteString, message: ⇒ String): Unit = ()

  def schedulerStep(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, moleculesPresent: ⇒ String): Unit = ()

  def reactionScheduled(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, reaction: ReactionString, inputs: ⇒ String, remainingMols: ⇒ String): Unit = ()

  def noReactionScheduled(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString): Unit = ()

  def reactionStarted(rsId: ReactionSiteId, rsString: ReactionSiteString, reaction: ReactionString, inputs: ⇒ String): Unit = ()

  def reactionFinished(rsId: ReactionSiteId, rsString: ReactionSiteString, reaction: ReactionString, inputs: ⇒ String, status: ReactionExitStatus): Unit = ()

  def errorReport(rsId: ReactionSiteId, rsString: ReactionSiteString, message: ⇒ String, printToConsole: Boolean = false): Unit = ()

  def reportDeadlock(poolName: String, maxPoolSize: Int, blockingCalls: Int, reactionInfo: ReactionString): Unit = ()
}

/** This [[Reporter]] never prints any messages at all.
  *
  */
object NoopSilentReporter extends Reporter(NoopLogTransport)

/** This trait prints no messages except errors.
  *
  */
trait ReportErrors extends ReportEvents {
  override def errorReport(rsId: ReactionSiteId, rsString: ReactionSiteString, message: ⇒ String, enable: Boolean = false): Unit = {
    if (enable) this.log(s"${LocalDateTime.now}: Error: In $rsString: $message")
  }

  override def reactionSiteError(rsId: ReactionSiteId, rsString: ReactionSiteString, message: => String): Unit = {
    this.log(s"${LocalDateTime.now}: Error: In $rsString: $message")
  }

  override def reportDeadlock(poolName: String, maxPoolSize: Int, blockingCalls: Int, reactionInfo: ReactionString): Unit = {
    this.log(s"Error: deadlock occurred in pool $poolName ($maxPoolSize threads) due to $blockingCalls concurrent blocking calls, while running reaction {$reactionInfo}")
  }
}

trait ReportWarnings extends ReportEvents {
  override def reactionSiteWarning(rsId: ReactionSiteId, rsString: ReactionSiteString, message: => String): Unit = {
    this.log(s"${LocalDateTime.now}: Warning: In $rsString: $message")
  }
}

trait ReportReactionSites extends ReportEvents {
  override def reactionSiteCreated(rsId: ReactionSiteId, rsString: ReactionSiteString, startNs: Long, endNs: Long): Unit = {
    this.log(s"${LocalDateTime.now}: Info: Created reaction site $rsId: $rsString in ${endNs - startNs} ns")
  }
}

trait LogTransport {
  def log(message: String): Unit
}

object ConsoleLogTransport extends LogTransport {
  override def log(message: String): Unit = println(message)
}

object NoopLogTransport extends LogTransport {
  override def log(message: String): Unit = ()
}

object ConsoleErrorReporter extends Reporter(ConsoleLogTransport) with ReportErrors

object ConsoleErrorsAndWarningsReporter extends Reporter(ConsoleLogTransport) with ReportErrors with ReportWarnings

final class MemoryLogger extends LogTransport {
  /** Access the reporter's global message log. This is used by reaction sites to report errors, metrics, and debugging messages at run time.
    *
    * @return An [[Iterable]] representing the sequence of all messages in the message log.
    */
  def messages: Iterable[String] = messageLog.iterator().asScala.toIterable

  /** Clear the global error log used by all reaction sites to report runtime errors.
    *
    */
  def clearLog(): Unit = messageLog.clear()

  private val messageLog = new LinkedBlockingQueue[String]()

  override def log(message: String): Unit = messageLog.add(message)
}
