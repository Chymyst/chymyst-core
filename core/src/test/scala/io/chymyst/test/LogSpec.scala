package io.chymyst.test

import org.scalatest.{Args, FlatSpec, Status}

class LogSpec extends FlatSpec {
  protected override def runTest(testName: String, args: Args): Status = {
    val initTime = System.currentTimeMillis()
    println(s"*** Starting test $testName")
    val result = super.runTest(testName, args)
    println(s"*** Finished test $testName in ${System.currentTimeMillis() - initTime} ms")
    result
  }
}
