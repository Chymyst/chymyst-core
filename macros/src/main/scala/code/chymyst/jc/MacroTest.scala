package sample

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

case class Blob(left: (Int, Option[Int]), right: (Int, Int))

case class ResultBlob(left: PartialFunction[Any,Any], all: PartialFunction[Any,Any])

object MacroTest {

  // test
  def rawTree(x: Any): String = macro rawTreeImpl

  def rawTreeImpl(c: blackbox.Context)(x: c.Expr[Any]): c.universe.Tree = {
    import c.universe._
    val result = showRaw(x.tree)
    q"$result"
  }

  def problem(pf: PartialFunction[Blob,Any]): ResultBlob = macro problemImpl

  def problemImpl(c: blackbox.Context)(pf: c.Expr[PartialFunction[Blob,Any]]): c.universe.Tree = {
    import c.universe._

???
  }

}