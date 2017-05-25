package io.chymyst.jc

import scala.concurrent.duration.Duration

trait Semafor {
  def acquire(): Unit

  def acquire(timeout: Duration): Unit

  def unblock(): Unit

  def unblockCheckTimeout(): Boolean
}

class Semafor1 extends Semafor {
  override def acquire(): Unit = wait()

  override def acquire(timeout: Duration): Unit = ???

  override def unblock(): Unit = ???

  override def unblockCheckTimeout(): Boolean = ???
}