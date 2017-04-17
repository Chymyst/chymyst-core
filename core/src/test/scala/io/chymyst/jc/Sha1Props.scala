package io.chymyst.jc


import Core._

import org.scalacheck.Properties
import org.scalacheck.Prop.forAll

object Sha1Props extends Properties("Sha1") {

  private val md = getMessageDigest

  property("noHashCollisionLongs") = forAll { (a: Long, b: Long) =>
    a == b || getSha1(a.toString, md) != getSha1(b.toString, md)
  }
}