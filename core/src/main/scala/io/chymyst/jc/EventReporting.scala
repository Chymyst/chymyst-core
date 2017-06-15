package io.chymyst.jc

import java.util.concurrent.LinkedBlockingQueue

import io.chymyst.jc.Core._

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.concurrent.duration.Duration

abstract class Reporter(logTransport: LogTransport) extends EventReporting {
  @inline def log(message: String): Unit = {
    logTransport.log(message): @inline
  }
}

trait EventReporting {
  def log(message: String): Unit // This method remains abstract, all others have default no-op implementations.

  def emitted(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String, moleculesPresent: ⇒ String): Unit = ()

  def removed(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String, moleculesPresent: ⇒ String): Unit = ()

  def replyReceived(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String): Unit = ()

  def replyTimedOut(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, timeout: Duration): Unit = ()

  def reactionSiteCreated(rsId: ReactionSiteId, rsString: ReactionSiteString, startNs: Long, endNs: Long): Unit = ()

  def reactionSiteError(rsId: ReactionSiteId, rsString: ReactionSiteString, message: ⇒ String): Unit = ()

  def reactionSiteWarning(rsId: ReactionSiteId, rsString: ReactionSiteString, message: ⇒ String): Unit = ()

  def schedulerStep(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, moleculesPresent: ⇒ String): Unit = ()

  def reactionScheduled(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, reaction: ReactionString, inputs: ⇒ String, remainingMols: ⇒ String): Unit = ()

  def reactionRescheduled(rsId: ReactionSiteId, rsString: ReactionSiteString, reaction: ReactionString, inputs: ⇒ String, remainingMols: ⇒ String): Unit = ()

  def noReactionScheduled(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, remainingMols: ⇒ String): Unit = ()

  def reactionStarted(rsId: ReactionSiteId, rsString: ReactionSiteString, threadName: String, reaction: ReactionString, inputs: ⇒ String): Unit = ()

  def reactionFinished(rsId: ReactionSiteId, rsString: ReactionSiteString, reaction: ReactionString, inputs: ⇒ String, status: ReactionExitStatus): Unit = ()

  def errorReport(rsId: ReactionSiteId, rsString: ReactionSiteString, message: ⇒ String, printToConsole: Boolean = false): Unit = ()

  def reportDeadlock(poolName: String, maxPoolSize: Int, blockingCalls: Int, reactionInfo: ReactionString): Unit = ()

  def warnTooManyThreads(poolName: String, threadCount: Int): Unit = ()
}

/** This trait prints no messages except errors.
  *
  */
trait ReportErrors extends EventReporting {
  override def errorReport(rsId: ReactionSiteId, rsString: ReactionSiteString, message: ⇒ String, enable: Boolean = false): Unit = {
    if (enable) log(s"Error: In $rsString: $message")
  }

  override def reactionSiteError(rsId: ReactionSiteId, rsString: ReactionSiteString, message: ⇒ String): Unit = {
    log(s"Error: In $rsString: $message")
  }

}

trait ReportMinorErrors extends EventReporting {
  override def reportDeadlock(poolName: String, maxPoolSize: Int, blockingCalls: Int, reactionInfo: ReactionString): Unit = {
    log(s"Warning: deadlock occurred in pool $poolName ($maxPoolSize threads) due to $blockingCalls concurrent blocking calls, while running reaction {$reactionInfo}")
  }

  override def warnTooManyThreads(poolName: String, threadCount: Int): Unit = {
    log(s"Warning: In $poolName: It is dangerous to further increase the pool size, which is now $threadCount")
  }
}

trait ReportWarnings extends EventReporting {
  override def reactionSiteWarning(rsId: ReactionSiteId, rsString: ReactionSiteString, message: ⇒ String): Unit = {
    log(s"Warning: In $rsString: $message")
  }
}

trait ReportReactionSites extends EventReporting {
  override def reactionSiteCreated(rsId: ReactionSiteId, rsString: ReactionSiteString, startNs: Long, endNs: Long): Unit = {
    log(s"Debug: Created reaction site $rsId: $rsString at $startNs ns, took ${endNs - startNs} ns")
  }
}

trait DebugReactionSites extends EventReporting {
  override def reactionScheduled(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, reaction: ReactionString, inputs: ⇒ String, remainingMols: ⇒ String): Unit = {
    log(s"Debug: In $rsString: scheduled reaction {$reaction} for molecule $mol, inputs [$inputs], remaining molecules [$remainingMols]")
  }

  override def reactionRescheduled(rsId: ReactionSiteId, rsString: ReactionSiteString, reaction: ReactionString, inputs: ⇒ String, remainingMols: ⇒ String): Unit = {
    log(s"Debug: In $rsString: repeated scheduled reaction {$reaction}, inputs [$inputs], remaining molecules [$remainingMols]")
  }

  override def noReactionScheduled(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, remainingMols: ⇒ String): Unit = {
    log(s"Debug: In $rsString: no reactions scheduled for molecule $mol, molecules present: [$remainingMols]")
  }

  override def schedulerStep(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, moleculesPresent: ⇒ String): Unit = {
    log(s"Debug: In $rsString: scheduler looks for reactions for molecule $mol, molecules present: [$moleculesPresent]")
  }
}

trait DebugReactions extends EventReporting {
  override def reactionStarted(rsId: ReactionSiteId, rsString: ReactionSiteString, threadName: String, reaction: ReactionString, inputs: ⇒ String): Unit = {
    log(s"Info: In $rsString: started reaction {$reaction} with inputs [$inputs]")
  }

  override def reactionFinished(rsId: ReactionSiteId, rsString: ReactionSiteString, reaction: ReactionString, inputs: ⇒ String, status: ReactionExitStatus): Unit = {
    log(s"Info: In $rsString: finished reaction {$reaction} with inputs [$inputs], status $status")
  }
}

trait DebugMolecules extends EventReporting {
  override def emitted(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String, moleculesPresent: ⇒ String): Unit = {
    log(s"Debug: In $rsString: emitted molecule $mol($molValue), molecules present: [$moleculesPresent]")
  }

  override def removed(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String, moleculesPresent: ⇒ String): Unit = {
    log(s"Debug: In $rsString: removed molecule $mol($molValue), molecules present: [$moleculesPresent]")
  }
}

trait DebugBlockingMolecules extends EventReporting {
  override def replyReceived(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String): Unit = {
    log(s"Debug: In $rsString: molecule $mol received reply value: $molValue")
  }

  override def replyTimedOut(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, timeout: Duration): Unit = {
    log(s"Debug: In $rsString: molecule $mol timed out waiting ${timeout.toMillis} ms for reply")
  }
}

trait LogTransport {
  def log(message: String): Unit
}

object ConsoleLogOutput extends LogTransport {
  override def log(message: String): Unit = println(message)
}

// Now we can easily define reporters. We just specify the log transport and the event reporting traits.

class ErrorReporter(logTransport: LogTransport) extends Reporter(logTransport) with ReportErrors

object ConsoleErrorReporter extends ErrorReporter(ConsoleLogOutput)

class ErrorsAndWarningsReporter(logTransport: LogTransport) extends Reporter(logTransport)
  with ReportErrors
  with ReportMinorErrors
  with ReportWarnings

object ConsoleErrorsAndWarningsReporter extends ErrorsAndWarningsReporter(ConsoleLogOutput)

class DebugAllReporter(logTransport: LogTransport) extends Reporter(logTransport)
  with ReportErrors
  with ReportMinorErrors
  with ReportWarnings
  with ReportReactionSites
  with DebugReactionSites
  with DebugMolecules
  with DebugReactions
  with DebugBlockingMolecules

object ConsoleDebugAllReporter extends DebugAllReporter(ConsoleLogOutput)

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
