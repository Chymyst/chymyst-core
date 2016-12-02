package code.winitzki.benchmark

import code.winitzki.benchmark.Common._
import code.winitzki.jc.FixedPool
import code.winitzki.jc.Macros._
import code.winitzki.jc.JoinRun._
import org.scalatest.{FlatSpec, Matchers}

/** This test will shutdown the default thread pools and check that no reactions can occur afterwards.
  *
  * This test suite is run last, after all other tests, and hopefully will be able to shutdown the test suites in CI.
  */
class ZZZShutdownSpec extends FlatSpec with Matchers {

  it should "fail to schedule reactions after shutdown of default thread pools" in {

    defaultJoinPool.shutdownNow()
    defaultReactionPool.shutdownNow()

    val x = m[Unit]
    join(runSimple{ case x(()) => })

    val thrown = intercept[Exception] {
      x()
    }
    thrown.getMessage shouldEqual "In Join{a => ...}: Cannot inject molecule x since join pool is not active"
  }
}
