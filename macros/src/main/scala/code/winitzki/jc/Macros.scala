package code.winitzki.jc

import scala.language.experimental.macros
import scala.reflect.macros._

object Macros {
  def mkval(valbody: Nothing => Any): Any = macro mkvalImpl

  def mkvalImpl(c: blackbox.Context)(valbody: c.Tree) = {
    import c.universe._

//    val (varname, realbody) = valbody match {
//      case q"${x: TermName} => $b" => (x, b)
//    }

    val q"(..$params) => $realbody" = valbody
    val List(q"$_ val $varname: $vartype") = params
    println(s"Debug: got varname=$varname, vartype=$vartype, realbody=$realbody")

    val thetype = tq"$vartype"

//    val result = (q"""object $varname { self => val $varname: $thetype = $realbody }""")
    val result = q"""val $varname : $thetype = $realbody """
    // a block was implicitly created.
    // No way to create a new val or object definition via macro! The error is obscure: the AST "val x = 0" has type <notype>,
    // and we can't typecheck the expression "c.Expr()" with that. We can't say "c.Expr[notype]" since there is no such type.

    // also note that incremental compilation is screwed: to force recompilation of macro code, need to force recompilation of dependent main code.

    // def macros seem to always return expressions?

//    println(s"Debug: returning result=${show(result.tree)}")
    result
  }


  def impl(c: whitebox.Context) = {
    import c.universe._
    c.Expr[Unit](q"""println("Hello World")""")
  }

  def hello: Unit = macro impl
  // Returns the tree of `a` after the typer, printed as source code.
  def desugar(a: Any): String = macro desugarImpl

  def desugarImpl(c: whitebox.Context)(a: c.Expr[Any]) = {
    import c.universe._

    val s = show(a.tree)
    c.Expr(
      Literal(Constant(s))
    )
  }

  def desugarF[T](a: Any => T): String = macro desugarFImpl

  def desugarFImpl(c: blackbox.Context)(a: c.Expr[Any]) = {
    import c.universe._

    val s = show(a.tree)
    c.Expr[String](q"$s")

  }

  def joindef[T](b: PartialFunction[Any, Any]): String = macro joindefImpl

  def joindefImpl(c: blackbox.Context)(b: c.Expr[PartialFunction[Any, Any]]) = {
    import c.universe._
    val s = showRaw(b.tree)
    c.Expr[String](q"$s")
  }

  def joindefT[T](b: PartialFunction[Any, Any]): T = macro joindefTImpl

  def joindefTImpl(c: blackbox.Context)(b: c.Tree): c.Tree = {
    import c.universe._
    val s = showRaw(b) + " *** desugared: *** " + show(b)
    q"$s"
  }
}
