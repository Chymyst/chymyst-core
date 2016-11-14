package code.winitzki.jc

import scala.collection.immutable
import scala.collection.mutable
import scala.reflect.ClassTag

// This is a stopgap measure. This entire code should be removed once a macro is implemented to introspect the case expressions in reactions.

// based on the discussion in http://stackoverflow.com/questions/5260298/how-can-i-obtain-the-default-value-for-a-type-in-scala
/*

// This works fine only when called explicitly, e.g. defaultValue[Int]. But this doesn't work inside a type-parameterized function, e.g.
 def getThat[T] = {
   defaultValue[T] // this will always be "null" even if we call getThat[Int] explicitly
 }

class DefaultValue[+A](val default: A)

trait LowerPriorityImplicits {
  // Stop AnyRefs from clashing with AnyVals
  implicit def defaultNull[A <: AnyRef]:DefaultValue[A] = new DefaultValue[A](null.asInstanceOf[A])
}

object DefaultValue extends LowerPriorityImplicits {
  implicit object DefaultDouble extends DefaultValue[Double](0.0)
  implicit object DefaultFloat extends DefaultValue[Float](0.0F)
  implicit object DefaultInt extends DefaultValue[Int](0)
  implicit object DefaultLong extends DefaultValue[Long](0L)
  implicit object DefaultShort extends DefaultValue[Short](0)
  implicit object DefaultByte extends DefaultValue[Byte](0)
  implicit object DefaultChar extends DefaultValue[Char]('\u0000')
  implicit object DefaultBoolean extends DefaultValue[Boolean](false)
  implicit object DefaultUnit extends DefaultValue[Unit](())
  implicit object DefaultString extends DefaultValue[String]("")

  implicit def defaultSeq[A]: DefaultValue[immutable.Seq[A]] = new DefaultValue[immutable.Seq[A]](immutable.Seq())
  implicit def defaultSet[A]: DefaultValue[immutable.Set[A]] = new DefaultValue[immutable.Set[A]](immutable.Set())
  implicit def defaultMap[A, B]: DefaultValue[immutable.Map[A, B]] = new DefaultValue[immutable.Map[A, B]](immutable.Map[A, B]())

  implicit def defaultMutableSeq[A]: DefaultValue[mutable.Seq[A]] = new DefaultValue[mutable.Seq[A]](mutable.Seq())
  implicit def defaultMutableSet[A]: DefaultValue[mutable.Set[A]] = new DefaultValue[mutable.Set[A]](mutable.Set())
  implicit def defaultMutableMap[A, B]: DefaultValue[mutable.Map[A, B]] = new DefaultValue[mutable.Map[A, B]](mutable.Map[A, B]())

  implicit def defaultOption[A]: DefaultValue[Option[A]] = new DefaultValue[Option[A]](None)

  implicit def defaultList[A]: DefaultValue[List[A]] = new DefaultValue[List[A]](List(): List[A])

  def defaultValue0[A](implicit value: DefaultValue[A]): A = value.default
}
*/

object DefaultValue {
  def defaultValue[T: ClassTag]: T = {
    val cTag = implicitly[ClassTag[T]].runtimeClass.toString
    cTag match {
      case "void" => ().asInstanceOf[T]
      case "boolean" => false.asInstanceOf[T]
      case "byte" => (0: Byte).asInstanceOf[T]
      case "short" => (0: Short).asInstanceOf[T]
      case "char" => '\0'.asInstanceOf[T]
      case "int" => 0.asInstanceOf[T]
      case "long" => 0L.asInstanceOf[T]
      case "float" => 0.0F.asInstanceOf[T]
      case "double" => 0.0.asInstanceOf[T]
      case "class java.lang.String" => "".asInstanceOf[T]

      case "class scala.Option" => None.asInstanceOf[T]
      case "interface scala.collection.Set" | "interface scala.collection.immutable.Set" =>
        Set().asInstanceOf[T]
      case "interface scala.collection.mutable.Set" =>
        mutable.Set().asInstanceOf[T]
      case "interface scala.collection.Seq" | "interface scala.collection.immutable.Seq" =>
        Seq().asInstanceOf[T]
      case "interface scala.collection.mutable.Seq" =>
        mutable.Seq().asInstanceOf[T]
      case "interface scala.collection.Map" | "interface scala.collection.immutable.Map" =>
        Map().asInstanceOf[T]
      case "interface scala.collection.mutable.Map" =>
        mutable.Map().asInstanceOf[T]

      case _ => null.asInstanceOf[T]
    }
  }

}
