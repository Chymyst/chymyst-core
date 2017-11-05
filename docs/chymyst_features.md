<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Main features of `Chymyst Core`

`Chymyst Core` implements Join Calculus similarly to [JoCaml](http://jocaml.inria.fr), with some extensions in both syntax and semantics.

## Concise declarative syntax 

`Chymyst Core` provides an embedded Scala DSL for chemical machine definitions.
Example code looks like this:

```scala
import io.chymyst.jc._

val s = m[Int] // declare a non-blocking molecule s
val c = b[Int, Int] // declare a blocking molecule c
site( // declare a reaction site
  go { case s(x) + c(y, reply) ⇒
    s(x + y); reply(x)
  }
)
s(1) // emit non-blocking molecule s with value 1

```

As a baseline reference, the most concise syntax for JC is available in [JoCaml](http://jocaml.inria.fr), which uses a modified OCaml compiler.
The equivalent reaction definition in JoCaml looks like this:

```ocaml
def s(x) & c(y) =  // declare a reaction site as well as molecules s and c
   s(x + y) & reply x to c
spawn s(1)  // emit non-blocking molecule s with value 1

```

In the JoCaml syntax, `s` and `c` are declared implicitly, together with the reaction, and type inference fixes the types of their values.
Implicit declaration of molecule emitters (“channels”) is not possible in `Chymyst` because Scala macros cannot insert new top-level name declarations into the code.
For this reason, `Chymyst` requires explicit declarations of molecule types (for example, `val c = b[Int, Int]`).

However, `Chymyst` does not use keywords such as `spawn` or `reply/to`.

## Compile-time checks for blocking molecules

Reactions that consume a blocking molecule are required to send a reply corresponding to the consumed blocking molecule.
This requirement applies separately to each consumed blocking molecule.

`Chymyst` generates a compile-time error if

- a reaction consumes a blocking molecule but does not have code that sends a reply, for example

```scala
// The `reply` emitter is not used at all.
go { case a(x) + f(_, reply) ⇒ a(x + 1) }

```

- a reaction has code that sends a reply but this code is not unconditional, for example  

```scala
// This code will not reply when x < 0.
go { case a(x) + f(_, reply) ⇒ if (x >= 0) reply(x) else a(0) }

```

- a reaction has code that uses the `reply` emitter within a closure or as a value that could be used outside the reaction, for example 

```scala
// Store the `reply` emitter in a data structure.
go { case a(x) + f(_, reply) ⇒ queue.add(reply); reply(x) }
// This code might reply more than once, or not at all.
go { case c(n) + g(_, reply) ⇒ (1 to n).map(i ⇒ reply(i)) }

```

These compile-time checks avoid common errors associated with blocking molecules.

## Arbitrary input patterns

In `Chymyst`'s Scala DSL, a reaction's input patterns is a `case` clause in a partial function.
Within the limits of the Scala syntax, reactions can define arbitrary input patterns.
 
### Unrestricted pattern matching

Reactions can use pattern matching expressions as well as guard conditions for selecting molecule values:

```scala
val c = m[Option[Int]]
val d = m[(String, List[String])]

go { case c(Some(x)) + d( s@("xyz", List(p, q, r)) ) 
      if x > 0 && p.length > q.length ⇒
      // Reaction will start only if the patterns match and the condition holds.
      // Reaction body can use pattern variables x, s, p, q, r.
}

```

### Nonlinear input patterns

Reactions can use repeated input molecules (“nonlinear” input patterns):

```scala
val c = m[Int]

go { case c(x) + c(y) + c(z) if x > y && y > z ⇒ c(x - y + z) }

```

Some concurrent algorithms are more easily expressed using repeated input molecules.

### Nonlinear blocking replies

A reaction can consume any number of blocking molecules at once, and each blocking molecule will receive its own reply.

For example, the following reaction consumes 3 blocking molecules `f`, `f`, `g` and exchanges the values caried by the two `f` molecules:

```scala
val f = b[Int, Int]
val g = b[Unit, Unit]

go { case f(x1, replyF1) + f(x2, replyF2) + g(_, replyG) ⇒
   replyF1(x2); replyF2(x1); replyG()
}

```

This reaction is impossible to write using JoCaml-style syntax `reply x to f`:
In that syntax, we cannot identify which of the copies of `f` should receive which reply value.
If JoCaml supported nonlinear input patterns, we could do this in JoCaml syntax:

```ocaml
def f(x1) + f(x2) + g() =>
  reply x2 to f; reply x1 to f; reply () to g

```

However, this code cannot specify that the reply value `x2` should be sent to the process that emitted `f(x1)` rather than to the process that emitted `f(x2)`.

## Reactions are values

Reactions are not merely `case` clauses but locally scoped values of type `Reaction`. The `go()` call is a macro that creates reaction values:

```scala
val c = m[Int]
val reaction: Reaction = go { case c(x) ⇒ println(x) }
// Declare a reaction, but do not run anything yet.

```

Users can build reaction sites incrementally, constructing, say, an array of `n` reaction values, where `n` is a run-time parameter.
Then a reaction site can be declared using the resulting array of reaction values.
Nevertheless, reactions and reaction sites are immutable once declared.

```scala
val reactions: Seq[Reaction] = ???
site(reactions: _*) // Activate all reactions.

```

Since molecule emitters are local values, one can also define `n` different molecules, where `n` is a run-time parameter.
There is no limit on the number of reactions in one reaction site, and no limit on the number of different molecules.

However, input molecules, input patterns, and guard conditions for each reaction must be defined statically.
It is impossible to define a reaction that consumes `n` input molecules, where `n` is a run-time parameter.

## Timeouts for blocking molecules

Emitting a blocking molecule will block forever if no reactions consume that molecule.
When this behavior is not desirable, the code can time out on that blocking call:

```scala
val f = b[Unit, Int]

site(...) // define some reactions that consume f

val result: Option[Int] = f.timeout()(200 millis)
// will return None on timeout

```

If the timeout occurs, the blocking molecule does not receive any reply value.

At this point, there can be two possibilities:

- no reaction consuming `f()` was able to start so far
- a reaction consuming `f()` already started but did not yet send a reply value 

If no reaction was able to start so far, the timeout on `f()` will cause `Chymyst` to remove the relevant copy of `f()` from the chemical soup,
so that no reaction would attempt to send a reply that the caller is no longer waiting for.

If a reaction already started, it is too late to remove the copy of `f()` from the soup.
The reaction will proceed to send a reply even though the caller is no longer waiting for it.

In some cases, it may be necessary for the reaction to know that the caller has timed out.
The reply action can check whether the timeout occurred because the reply emitter returns a `Boolean` value:

```scala
val f = b[Unit, Int]
go { f(_, reply) ⇒
// Attempt to reply with `123`; return `false` if caller timed out.
  val status = reply(123)
  if (!status) ??? // caller timed out, maybe need to clean up, etc.
}

```

## Static analysis for correctness and optimization

`Chymyst` uses the `go()` macro for gathering extensive information about the user's reaction code.
This information is used to perform static analysis of reactions, both at compile time and at "early" run time (after creating reaction sites but before any reactions are activated).
Thus `Chymyst` is able to detect some classes of deadlock or livelock automatically:

```scala
val a = m[Int]
val c = m[Unit]

// Does not compile: "Unconditional livelock due to a(x)"
go { case a(x) ⇒ c(); a(x+1) }

```

The static analysis is used to enforce constraints such as the uniqueness of the reply to blocking molecules:

```scala
val a = m[Int]
val f = b[Unit, Int]

// Does not compile: "Blocking molecules should receive unconditional reply"
go { case f(_, r) + a(x) ⇒ if (x > 0) r(x) }

// Compiles successfully because the reply is always sent.
go { case f(_, r) + a(x) ⇒ if (x > 0) r(x) else r(-x) }

```

Common cases of invalid chemical definitions are flagged either at compile time, or as run-time errors that occur after defining a reaction site and before starting any processes.
Other errors are flagged at run time, such as:

- a molecule was emitted without defining any reaction site for that molecule
- a reaction cannot start because its thread pool was shut down
- a reaction produced an exception and failed to send a reply

The results of static analysis are also used to optimize the scheduling of reactions at run time.
For instance, reactions are scheduled significantly faster if they have no cross-molecule guard conditions.

## Thread pools

`Chymyst` implements fine-grained threading control.
Each reaction site and each reaction can be run on a different, separate thread pool if required.
The user can control the number of threads in thread pools.

```scala
val tp1 = FixedPool(1)
val tp8 = BlockingPool(8)

site(tp8)( // reaction site runs on tp8
  go { case a(x) => ... } onThreads tp1, // this reaction runs on tp1
  go { ... } // all other reactions run on tp8
 )

```

The thread pools of class `BlockingPool` are called "blocking" because they will automatically adjust the number of active threads if blocking operations occur.
So, blocking operations do not decrease the degree of parallelism.

Thread pools also include features for thread priority control.

## Graceful shutdown

When a `Chymyst`-based program needs to exit, it can shut down the thread pools that run reactions.

```scala
val tp = BlockingPool(8)

// define reactions and run them

tp.shutdownNow() // all reactions running on `tp` will stop

```

## Non-deterministic choice

Whenever a molecule can start several reactions, the reaction is chosen arbitrarily.

Whenever a reaction can consume several different copies of input molecules, the actually consumed copies are chosen arbitrarily. 

## Pipelined molecules

Some reactions consume molecules in such a way that the reaction scheduler only needs to examine a single molecule instance in order to decide whether reactions can start.
`Chymyst` automatically detects such molecules and implements an ordered queue for storing the molecule instances.
Such molecules are called "pipelined"; reactions with these molecules are scheduled faster and more deterministically.

## Fault tolerance

Reactions marked as fault-tolerant will be automatically restarted if exceptions are thrown.

## Debugging

The execution of reactions can be traced via logging levels per reaction site.
Due to automatic naming of molecules and static analysis, debugging can print information about reaction flow in a visual way.

