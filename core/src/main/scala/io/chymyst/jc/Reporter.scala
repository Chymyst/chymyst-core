package io.chymyst.jc

import java.time.LocalDateTime
import java.util.concurrent.LinkedBlockingQueue

import io.chymyst.jc.Core._

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.concurrent.duration.Duration

abstract class Reporter {
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

  /** Access the global error log used by all reaction sites to report runtime errors.
    *
    * @return An `Iterable` representing the complete error log.
    */
  def globalErrorLog: Iterable[String] = errorLog.iterator().asScala.toIterable

  /** Clear the global error log used by all reaction sites to report runtime errors.
    *
    */
  def clearGlobalErrorLog(): Unit = errorLog.clear()

  private val errorLog = new LinkedBlockingQueue[String]()
}

/** This [[Reporter]] prints no messages at all.
  *
  */
object NoopSilentReporter extends Reporter

/** This [[Reporter]] prints no messages except errors, which are logged to console.
  *
  */
trait ConsoleErrorReporter extends Reporter {
  override def errorReport(rsId: ReactionSiteId, rsString: ReactionSiteString, message: String, printToConsole: Boolean = false): Unit = {
    if (printToConsole) println(s"${LocalDateTime.now}: Error: In $rsString: $message")
  }

  override def reportDeadlock(poolName: String, maxPoolSize: Int, blockingCalls: Int, reactionInfo: ReactionString): Unit = {
    println(s"Error: deadlock occurred in fixed pool ($maxPoolSize threads) due to $blockingCalls concurrent blocking calls, reaction: $reactionInfo")
  }
}

object ConsoleErrorReporter extends ConsoleErrorReporter

class ConsoleLoggerReporter(logLevel: Int) extends ConsoleErrorReporter {

}

object ConsoleLoggerReporter {
  def apply(logLevel: Int): ConsoleLoggerReporter = new ConsoleLoggerReporter(logLevel)
}

