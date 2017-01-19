package sample

import org.scalatest.{FlatSpec, Matchers}

class HelloWorldChymystSpec extends FlatSpec with Matchers {

  behavior of "hello world application"

  it should "start reactions" in {
    HelloWorldChymyst.startReactions() shouldEqual true
  }
}
