package io.chymyst.lab

import io.chymyst.jc._

object Lab {
  def litmus[T](tp: Pool): (M[T], B[Unit, T]) = {
    val signal = m[T]
    val fetch = b[Unit, T]
    site(tp)(
      go { case signal(x) + fetch(_, r) ⇒ r(x) }
    )
    (signal, fetch)
  }
}
