package code.winitzki.jc

import org.scalatest.{FlatSpec, Matchers}
import DefaultValue._

import scala.collection.immutable
import scala.collection.mutable

class DefaultValueSpec extends FlatSpec with Matchers {

  behavior of "#defaultValue"

  it should "have default value for some types" in {
    val b = defaultValue[Int]
    b shouldEqual 0

    val c = defaultValue[String]
    c shouldEqual ""
  }

  it should "have default value for option type" in {
    val c = defaultValue[Option[Int]]
    c shouldEqual None
  }

  it should "have default value for immutable sequence type" in {
    val c = defaultValue[immutable.Seq[Int]]
    c shouldEqual List()

  }

  it should "have default value for mutable sequence type" in {
    val c = defaultValue[mutable.Seq[Int]]
    c shouldEqual List()
  }

  it should "have default value for immutable set type" in {
    val c = defaultValue[immutable.Set[Int]]
    c shouldEqual Set()

  }

  it should "have default value for mutable set type" in {
    val c = defaultValue[mutable.Set[Int]]
    c shouldEqual Set()
  }

  it should "have default value for immutable map type" in {
    val c = defaultValue[immutable.Map[Int,Int]]
    c shouldEqual Map()

  }
  it should "have default value for mutable map type" in {
    val c = defaultValue[mutable.Map[Int,Int]]
    c shouldEqual Map()
  }

  it should "have null default value for tuple type" in {
    val c = defaultValue[(Int, String)]
    c shouldEqual null
  }

  it should "perform pattern-matching on default argument" in {

    val fNone: PartialFunction[Option[Int],Unit] = {
      case None => ()
    }

    val fSome: PartialFunction[Option[Int],Unit] = {
      case Some(x) => ()
    }

    val xOption = defaultValue[Option[Int]]

    fNone.isDefinedAt(xOption) shouldEqual true
    fSome.isDefinedAt(xOption) shouldEqual false

  }

  it should "perform pattern-matching on default argument with unapply" in {

    object Test1 {
      def unapply(x: Any): Option[Option[Int]] = x match {
        case _ => Some(defaultValue[Option[Int]])
      }
    }

    val fNone: PartialFunction[Unit, Unit] = {
      case Test1(None) => ()
    }

    val fSome: PartialFunction[Unit, Unit] = {
      case Test1(Some(_)) => ()
    }

    fNone.isDefinedAt(()) shouldEqual true
    fSome.isDefinedAt(()) shouldEqual false

  }

}
