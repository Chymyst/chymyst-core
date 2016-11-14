package code.winitzki.jc

import scala.language.experimental.macros
import scala.reflect.macros._
import scala.reflect.NameTransformer.LOCAL_SUFFIX_STRING

import JoinRun._

object Macros {

  type theContext = blackbox.Context

  def getName: String = macro getNameImpl

  def getEnclosingName(c: theContext): String =
    c.internal.enclosingOwner.name.decodedName.toString
      .stripSuffix(LOCAL_SUFFIX_STRING).stripSuffix("$lzy")

  def getNameImpl(c: theContext): c.Expr[String] = {
    import c.universe._

    val s = getEnclosingName(c)

    c.Expr[String](q"$s")
  }

  def jA[T]: JA[T] = macro jAImpl[T]

  def jAImpl[T: c.WeakTypeTag](c: theContext): c.Expr[JA[T]] = {
    import c.universe._
    val s = getEnclosingName(c)

    val t = c.weakTypeOf[T]

    c.Expr[JA[T]](q"new JA[$t](Some($s))")
  }

  def jS[T, R]: JS[T, R] = macro jSImpl[T, R]

  def jSImpl[T: c.WeakTypeTag, R: c.WeakTypeTag](c: blackbox.Context): c.Expr[JS[T, R]] = {
    import c.universe._
    val s = c.internal.enclosingOwner.name.decodedName.toString.stripSuffix(LOCAL_SUFFIX_STRING).stripSuffix("$lzy")

    val t = c.weakTypeOf[T]
    val r = c.weakTypeOf[R]

    c.Expr[JS[T, R]](q"new JS[$t,$r](Some($s))")
  }

  def findInputs(arg: UnapplyArg => Unit): String = macro findInputsImpl

  def findInputsImpl(c: theContext)(arg: c.Expr[UnapplyArg => Unit]) = {
    import c.universe._
    q"${show(arg)}"
  }

}
