package io.chymyst.test

import io.chymyst.jc._

/** This test will shutdown the default thread pools and check that no reactions can occur afterwards.
  *
  * This test suite is run last, after all other tests, and hopefully will be able to shutdown the test suites in CI.
  */
class ShutdownSpec extends LogSpec {

  behavior of "pool threads"

  it should "not fail to schedule reactions after a timeout when fixed pool may free threads" in {

    val pool = FixedPool()

    val x = m[Unit]
    site(pool)(go { case x(()) => })
    x()
    Thread.sleep(5000)
    x()
    pool.shutdownNow()
  }

  it should "not fail to schedule reactions after a timeout when BlockingPool may free threads" in {

    val pool = BlockingPool()

    pool.currentPoolSize shouldEqual cpuCores

    val x = m[Unit]
    site(pool)(go { case x(()) => })
    x()
    pool.currentPoolSize shouldEqual cpuCores
    Thread.sleep(5000)
    pool.currentPoolSize shouldEqual cpuCores
    x()
    pool.shutdownNow()
  }

  it should "not fail to schedule reactions after shutdown of custom reaction pool" in {

    val pool = FixedPool(2)
    pool.shutdownNow()

    val x = m[Unit]
    site(pool)(go { case x(()) => })
    the[Exception] thrownBy {
      x()
    } should have message "In Site{x → ...}: Molecule x() cannot be emitted because reaction pool FixedPool:pool is not active"
  }

  it should "fail to schedule reactions after shutdown of default thread pools" in {

    defaultPool.shutdownNow()

    val x = m[Unit]
    site(go { case x(_) => })

    the[Exception] thrownBy {
      x()
    } should have message "In Site{x → ...}: Molecule x() cannot be emitted because reaction pool BlockingPool:defaultPool is not active"
  }

}
