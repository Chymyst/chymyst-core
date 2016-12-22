package code.winitzki.test

import code.winitzki.jc._
import code.winitzki.jc.Macros._
import org.scalatest.{FlatSpec, Matchers}

/** This test will shutdown the default thread pools and check that no reactions can occur afterwards.
  *
  * This test suite is run last, after all other tests, and hopefully will be able to shutdown the test suites in CI.
  */
class ShutdownSpec extends FlatSpec with Matchers {

  it should "fail to schedule reactions after shutdown of custom join pool" in {

    val pool = new FixedPool(2)
    val pool2 = new FixedPool(2)
    pool2.shutdownNow()

    val x = m[Unit]
    site(pool,pool2)(go{ case x(()) => })

    val thrown = intercept[Exception] {
      x()
    }
    thrown.getMessage shouldEqual "In Site{x => ...}: Cannot emit molecule x() because join pool is not active"
    pool.shutdownNow()
  }

  it should "not fail to schedule reactions after shutdown of custom reaction pool" in {

    val pool = new FixedPool(2)
    val pool2 = new FixedPool(2)
    pool.shutdownNow()

    val x = m[Unit]
    site(pool,pool2)(go{ case x(()) => })

    x()

    pool2.shutdownNow() shouldEqual ()
  }

  it should "fail to schedule reactions after shutdown of default thread pools" in {

    defaultSitePool.shutdownNow()
    defaultReactionPool.shutdownNow()

    val x = m[Unit]
    site(go{ case x(()) => })

    val thrown = intercept[Exception] {
      x()
    }
    thrown.getMessage shouldEqual "In Site{x => ...}: Cannot emit molecule x() because join pool is not active"
  }
}
