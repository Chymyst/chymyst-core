package code.winitzki.jc


import Core._

import org.scalacheck.Properties
import org.scalacheck.Prop.forAll

object Sha1Props extends Properties("JoinRunUtilsSha1") {
  property("invariantForLongOrString") = forAll { (a: Long) =>
    getSha1(a) == getSha1(a.toString)
  }

  property("noHashCollisionLongs") = forAll { (a: Long, b: Long) =>
    a == b || getSha1(a) != getSha1(b)
  }
}