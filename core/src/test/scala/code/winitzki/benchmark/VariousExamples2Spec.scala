package code.winitzki.benchmark

import java.time.temporal.ChronoUnit
import java.time.LocalDateTime

import code.winitzki.jc.JoinRun._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

class VariousExamples2Spec extends FlatSpec with Matchers with TimeLimitedTests with BeforeAndAfter {

  val timeLimit = Span(10000, Millis)

  var initTime = LocalDateTime.now

  before {
    initTime = LocalDateTime.now
  }

  after {
    val elapsed = initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)
    println(s"Elapsed time: $elapsed")
  }

  it should "sort an array using concurrent merge sort more quickly with more threads" in {

  }
}
