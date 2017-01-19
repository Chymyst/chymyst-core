package sample

import code.chymyst.jc._

object HelloWorldChymyst extends App {
  // Define a function that starts some reactions, then call that function.

  def startReactions(): Boolean = {
    val tp = new FixedPool(4)

    val a = m[String]
    val get_status = b[Unit, Boolean]

    site(tp)(
      go { case a(message) + get_status(_, r) => println(message); r(true) }
    )

    a("Hello, Chymyst lab!")
    val status: Boolean = get_status()
    tp.shutdownNow()
    status
  }

  startReactions()
}
