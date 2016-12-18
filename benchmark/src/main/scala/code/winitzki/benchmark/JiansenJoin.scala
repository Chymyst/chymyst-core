package code.winitzki.benchmark

/* This file is copied from He Jiansen's ScalaJoin, see https://github.com/Jiansen/ScalaJoin.
This file is not a part of JoinRun. Only the benchmark application uses this file
for performance comparisons between JoinRun and ScalaJoin.

Some minor changes were made to accommodate updates in the Scala standard library since 2011.
*/

/** A module providing constracts for join patterns.
  *  Based on Philipp Haller's Scala Joins library <a href="http://lamp.epfl.ch/~phaller/joins/index.html"></a> with following improvements
  *
  *  -- providing uniform 'and' operator
  *  -- supporting pattern matching on messages
  *  -- supporting theoretically unlimited numbers of patterns in a single join definition
  *       (due to the limitation of current Scala compiler, there would be an upper
  *        limit for the number of cases statements in a single block)
  *  -- using simpler structure for the Join class
  *  -- preventing overriding defined join patterns in a join object
  *
  *  Limitations
  *  -- does not support subtyping on channel types
  *  -- in each join pattern, we don't check
  *     (a) if the body contains a reply to a synchronous channel which does not appear in the pattern part
  *     (b) if the all synchronous channels in the pattern part will get a reply.
  *
  *  @author  Jiansen HE
  *  @version 0.3.6, 14/11/2011
  */

import scala.collection.immutable.HashMap
import scala.collection.mutable.{HashSet, MutableList, Queue, Set, Stack}
import scala.reflect.ClassTag

// Base class for local channels
trait NameBase{
  // Type equality test
  def argTypeEqual(t:Any):Boolean
  def pendArg(arg:Any):Unit
  def popArg():Unit
}


/** Asynchronous local channel
  *
  *  @param owner  the Join object where this channel is defined
  *  @param argT   the descriptor the argument type
  */
class AsyName[Arg](implicit owner: Join, argT:ClassTag[Arg]) extends NameBase{

  def getQ:Queue[Arg] = {argQ}
  // Does not support subtyping
  override def argTypeEqual(t:Any) :Boolean = {
    //t = argT
    t.toString == argT.toString
  }

  override def pendArg(arg:Any):Unit = {
    argQ += arg.asInstanceOf[Arg]
  }

  override def popArg():Unit = {
    argQ.dequeue()
  }

  var argQ = new Queue[Arg] //queue of arguments pending on this name

  /** Pending message on this channel
    *
    *  @param  a  the message pending on this channel
    */
  def apply(a:Arg) :Unit = new Thread {synchronized{
    if(argQ.contains(a)){
      argQ += a
    }else {
      owner.trymatch(AsyName.this, a)// see if the new message will trigger any pattern
    }
  }}

  def unapply(attr:Any) : Option[Arg]= attr match { // for pattern matching
    case (ch:NameBase, arg:Any) => {
      if(ch == this){
        Some(arg.asInstanceOf[Arg])
      }else{
        None
      }
    }
    case (nameset: Set[NameBase], pattern:PartialFunction[Any, Any], fixedMsg:HashMap[NameBase, Any], tag:Int, banedName:NameBase) => {

      if (this == banedName) {return None}
      if(fixedMsg.contains(this)){
        Some(fixedMsg(this).asInstanceOf[Arg])
      }else{
        var checkedMsg = new HashSet[Arg]

        def matched(m:Arg):Boolean = {
          if (checkedMsg(m)) {false} // the message has been checked
          else { //   pattern cannot be fired without the presence of current channel
            //&& pattern can be fired when m is bound to the current channel
            checkedMsg += m
            (!(pattern.isDefinedAt(nameset, pattern, fixedMsg+((this, m)), tag+1, this))
              && (pattern.isDefinedAt((nameset, pattern, fixedMsg+((this, m)), tag+1, banedName))))
          }
        }

        var returnV:Option[Arg] = None
        argQ.span(m => !matched(m)) match {
          case (_, MutableList()) => { // no message pending on this channel may trigger the pattern
            returnV = None
          }
          case (ums, ms) => {
            //  println("\ntag "+tag+": fixedMsg = "+fixedMsg+" this = "+this+" argQ = "+argQ )  // for debugging
            val arg = ms.head // the message could trigger a pattern
            argQ = (((ums.+=:( arg )) ++ ms.tail).toQueue) // pop this message to the head of message queue
            if(/*tag == 1*/ true) {nameset.add(this); /*println(nameset)*/}
            returnV = Some(arg)
          }
        }
        checkedMsg.clear
        returnV
      }
    }
    case attr => {throw new Error("Failure arises when examining patterns "+attr)} // should not arrive here
  }
}

/** Synchronous local channel
  *
  *  @param owner  the Join object where this channel is defined
  *  @param argT   the descriptor the argument type
  *  @param resT   the descriptor the return type
  */
class SynName[Arg, R](implicit owner: Join, argT:ClassManifest[Arg], resT:ClassManifest[R]) extends NameBase{
  // does not support subtyping

  override def argTypeEqual(t:Any) :Boolean = t match {
    case (aT,rT) =>
      (argT.toString == aT.toString  && resT.toString == rT.toString )
    case _ => false
  }

  override def pendArg(arg:Any):Unit = {
    argQ += arg.asInstanceOf[(Int,Arg)]
  }

  override def popArg():Unit = synchronized {
    val arg = argQ.dequeue()
    //pushMsgTag(arg)
  }

  var argQ = new Queue[(Int,Arg)]  // argument queue
  var msgTags = new Stack[(Int,Arg)] // matched messages
  var resultQ = new Queue[((Int,Arg), R)] // results

  private object TagMsg{
    val map = new scala.collection.mutable.HashMap[Arg, Int]
    def newtag(msg:Arg):(Int,Arg) = {
      map.get(msg) match {
        case None =>
          map.update(msg,0)
          (0,msg)
        case Some(t) =>
          map.update(msg, t+1)
          (t+1, msg)
      }
    }
  }

  def pushMsgTag(arg:Any) = synchronized {
    //    println("push "+arg)
    msgTags.push(arg.asInstanceOf[(Int,Arg)])
  }

  def popMsgTag:(Int,Arg) = synchronized {
    if(msgTags.isEmpty) {
      wait()
      popMsgTag
    }else{
      msgTags.pop
    }
  }

  /** Pending message on this channel
    *
    *  @param  a  the message pending on this channel
    *  @return    the returned value
    */
  def apply(a:Arg) :R = {
    val m = TagMsg.newtag(a)
    argQ.find(msg => msg._2 == m._2) match{
      case None => owner.trymatch(this, m)
      case Some(_) => argQ += m
    }
    fetch(m)
  }

  /** reply value r to this channel
    *
    *  @param r the reply value
    */
  def reply(r:R):Unit = new Thread {resultQ.synchronized {
    //    println("reply "+r)
    resultQ.enqueue((msgTags.pop, r))
    resultQ.notifyAll()
  }}

  private def fetch(a:(Int,Arg)):R = resultQ.synchronized {
    if (resultQ.isEmpty || resultQ.front._1 != a){
      resultQ.wait(); fetch(a)
    }else{
      //notifyAll()
      val r = resultQ.dequeue()._2
      resultQ.notifyAll()
      r
    }
  }

  def unapply(attr:Any) : Option[Arg]= { attr match{ // for pattern matching
    case (ch:NameBase, arg:Any) => {
      if(ch == this){
        Some(arg.asInstanceOf[(Int,Arg)]._2)
      }else{
        None
      }
    }
    case (nameset: Set[NameBase], pattern:PartialFunction[Any, Any], fixedMsg:HashMap[NameBase, Any], dp:Int, banedName:NameBase) => {
      //  println("\ntag "+tag+": fixedMsg = "+fixedMsg+" this = "+this+" argQ = "+argQ )  // for debugging
      if (this == banedName) {return None}

      if(fixedMsg.contains(this)){
        Some(fixedMsg(this).asInstanceOf[(Int,Arg)]._2)
      }else{
        var checkedMsg = new HashSet[Arg]

        def matched(m:(Int,Arg)):Boolean = {
          if (checkedMsg(m._2)) {false} // the message has been checked
          else { //   pattern cannot be fired without the presence of current channel
            //&& pattern can be fired when m is bound to the current channel
            checkedMsg += m._2
            (!(pattern.isDefinedAt(nameset, pattern, fixedMsg+((this, m)), dp+1, this))
              && (pattern.isDefinedAt((nameset, pattern, fixedMsg+((this, m)), dp+1, banedName))))
          }
        }

        var returnV:Option[Arg] = None
        argQ.span(m => !matched(m)) match {
          case (_, MutableList()) => { // message no pending on this channel may trigger the pattern
            returnV = None
          }
          case (ums, ms) => {
            //  println("\ntag "+tag+": fixedMsg = "+fixedMsg+" this = "+this+" argQ = "+argQ )  // for debugging
            val arg = ms.head // the message could trigger a pattern
            argQ = (((ums.+=:( arg )) ++ ms.tail).toQueue) // pop this message to the head of message queue
            if(dp == 1) {nameset.add(this); pushMsgTag(arg)/*println(nameset)*/}
            returnV = Some(arg._2)
          }
        }
        checkedMsg.clear
        returnV
      }
    }
    case attr => {throw new Error("Failure arises when examining patterns "+attr)} // should not arrive here
  }
  }
}

object and{
  //  def unapply(attr:(Set[NameBase], Queue[(NameBase, Any)], PartialFunction[Any, Any], Int)) = {
  def unapply(attr:Any) = {
    Some(attr,attr)
  }

}

class Join {
  private var hasDefined = false
  //  private var lock : AnyRef = new Object()
  implicit val joinsOwner = this

  private var joinPat: PartialFunction[Any, Any] = _
  //  private var joinPat: PartialFunction[(Set[NameBase], Queue[(NameBase, Any)], PartialFunction[Any, Any], Int), Unit] = _
  //  def join(joinPat: PartialFunction[(Set[NameBase], Queue[(NameBase, Any)], PartialFunction[Any, Any], Int), Unit]) {
  def join(joinPat: PartialFunction[Any, Any]) {
    if(!hasDefined){
      this.joinPat = joinPat
      hasDefined = true
    }else{
      throw new Exception("Join definition has been set for"+this)
    }
  }

  def trymatch(ch:NameBase, arg:Any) = synchronized {
    var names: Set[NameBase] = new HashSet
    //println(ch +"   "+ arg)
    try{
      if(ch.isInstanceOf[SynName[Any, Any]]) {ch.asInstanceOf[SynName[Any,Any]].pushMsgTag(arg)}
      if(joinPat.isDefinedAt((ch, arg))){// optimization for singleton pattern
        joinPat((ch,arg))
      }else{
        if(ch.isInstanceOf[SynName[Any, Any]]){
          joinPat((names, this.joinPat, (new HashMap[NameBase, Any]+((ch, arg))), 1, new SynName))
          ch.asInstanceOf[SynName[Any,Any]].pushMsgTag(arg)
        }else{
          joinPat((names, this.joinPat, (new HashMap[NameBase, Any]+((ch, arg))), 1, new AsyName))
        }
        names.foreach(n => {
          if(n != ch) n.popArg
        })
      }
    }catch{
      case e:MatchError => {// no pattern is matched
        if(ch.isInstanceOf[SynName[Any, Any]]) {ch.asInstanceOf[SynName[Any,Any]].popMsgTag}
        ch.pendArg(arg)
      }
    }
  }
}
