package io.chymyst.jc

import scala.collection.mutable

class TimingHelper {
  private val factor = 100

  private var currentTimestampNs = 0L

  private var messages: mutable.ArrayBuffer[Long] = _

  def update(currentIteration: Int): Unit = synchronized {
    val ts = System.nanoTime()
    val elapsed = ts - currentTimestampNs
    currentTimestampNs = ts
    messages += (currentIteration + factor * elapsed)
  }

  def reset(): Unit = synchronized {
    messages = mutable.ArrayBuffer.empty[Long]
    messages.sizeHint(1000)
    currentTimestampNs = 0
  }

  def printAll(): String = synchronized {
    messages.update(0, 0)
    val total = messages.map(l ⇒ l / factor).sum
    messages.update(0, total * factor + 99)
    messages.map { l ⇒
      val iteration = l % factor
      val ts = l / factor
      f"[$iteration%02d] $ts% 10d ns"
    }.mkString("\n")
  }

  reset()
}
