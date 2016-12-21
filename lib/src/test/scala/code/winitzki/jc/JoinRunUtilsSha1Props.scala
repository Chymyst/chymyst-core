package code.winitzki.jc

import code.winitzki.jc.JoinRunUtils.getSha1
import org.scalacheck.Properties
import org.scalacheck.Prop.forAll

object JoinRunUtilsSha1Specification extends Properties("JoinRunUtilsSha1") {
  property("invariantForLongOrString") = forAll { (a: Long) =>
    getSha1(a) == getSha1(a.toString)
  }

  property("noHashCollisionLongs") = forAll { (a: Long, b: Long) =>
    a == b || getSha1(a) != getSha1(b)
  }
}