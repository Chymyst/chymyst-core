package code.chymyst.jc

import Macros.{m, b, go}
import org.scalatest.{FlatSpec, Matchers}

class GuardsSpec extends FlatSpec with Matchers {

  behavior of "guard conditions"

  it should "correctly split a guard condition" in {
    val a = m[Int]
    val bb = m[(Int, Option[Int])]
    val f = b[Unit, Unit]

    val result = go {
      case a(p) + a(y) + a(1) + bb((1, z)) + bb((t, Some(q))) + f(_, r) if y > 0 && q > 0 && t == p => r()
    }
    result.info.hasGuard shouldEqual GuardPresent(List(List('y), List('q), List('t, 'p)))
  }


}
