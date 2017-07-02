<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Molecules and emitters, in depth

## Molecule names

The names of molecules in `Chymyst` have no effect on any concurrent computations.
For instance, the runtime engine will not check that a molecule's name is not empty, or that the names of different molecules are different.
Molecule names are used only for debugging: they are printed when logging reactions and reaction sites.

There are two ways of assigning a name to a molecule:

- specify a name explicitly, by using a class constructor
- use the macros `m` and `b`

Here is an example of defining emitters using explicit class constructors and molecule names:

```scala
val counter = new M[Int]("counter")
val fetch = new B[Int, Int]("fetch")

```

This code is _completely equivalent_ to the shorter code written using macros:

```scala
val counter = m[Int]
val fetch = b[Int, Int]

```

These macros read the names `"counter"` and `"fetch"` from the surrounding code.
This functionality is intended as a syntactic convenience.

Each molecule emitter has a `toString` method, which returns the molecule's name.
For blocking molecules, the molecule's name is followed by `"/B"`.

```scala
val x = new M[Int]("counter")
val y = new B[Unit, Int]("fetch")

x.toString // returns “counter”
y.toString // returns “fetch/B”

```


## Molecules vs. molecule emitters

Molecules are emitted into the chemical soup using the syntax such as `c(123)`. Here, `c` is a value we define using a construction such as

```scala
val c = m[Int]

```

Any molecule emitted in the soup must carry a value.
For example, the molecule `c()` must carry an integer value.

So the value `c` we just defined is not a molecule in the soup.
The value `c` is a **molecule emitter**, — a function that, when called, will emit molecules of chemical sort `c` into the soup.
More precisely, an emitter call such as `c(123)` returns `Unit` and performs a _side effect_ that consists of emitting the molecule of sort `c` with value `123` into the soup.

The syntax `c(x)` is used in two different ways:

- in the left-hand side of a reaction, it is a pattern matching construction that matches on values of input molecules
- in the reaction body, it is an emitter call

In both cases, `c(x)` can be visualized as a molecule with value `x` that exists in the soup.

The chemistry-resembling syntax such as `c(x) + d(y)` is also used in two different ways:

- in the left-hand side of a reaction, it is a pattern matching construction that matches on values of several input molecules at once
- in the reaction body, it is syntactic sugar for several emitter calls, equivalent to `c(x); d(y)`

### Non-blocking molecules

If `c` defined as above as `val c = m[Int]`, the emitter `c` is **non-blocking**.
The emitter function call `c(123)` is non-blocking because it immediately returns a `Unit` value
and does not wait for any reaction involving `c(123)` to actually start.

The non-blocking emitter `c` defined above will have type `M[Int]` and can be also created directly using the class constructor:

```scala
val c = new M[Int]("c")

```

Molecules carrying `Unit` values can be emitted using the syntax such as `a()` rather than `a(())`, which is also valid.
The shorter syntax `a()` is provided as a purely syntactic convenience.

### Blocking molecules

For a **blocking** molecule, the emitter call will block until a reaction can start that consumes that molecule.

A blocking emitter is defined like this,

```scala
val f = b[Int, String]

```

Now `f` is a blocking emitter that takes an `Int` value and returns a `String`.

Blocking emitters are values of type `B[T, R]`, which is a subtype of `T ⇒ R`.
The emitter `f` could be equivalently defined by

```scala
val f = new B[Int, String]("f")

```

In this case, the user needs to provide the molecule name explicitly.

Once `f` is defined like this, an emitter call such as

```scala
val result = f(123)

```

will emit a molecule of sort `f` with value `123` into the soup.

After emitting `f(123)`, the process will become blocked until some reaction starts, consumes this molecule, and performs a **reply action**.

Since the type of `f` is `B[Int, String]`, the reply action must pass a `String` value to the reply function:

```scala
go { case c(x) + f(y, r) ⇒
  val replyValue = (x + y).toString 
  r(replyValue) 
}

```

The reply action consists of calling the **reply emitter** `r` with the reply value as its argument.

Only after the reply action, the process that emitted `f(123)` will become unblocked and the statement `val result = f(123)` will be completed.
The variable `result` will become equal to the string value that was sent as the reply.

## Remarks about the semantics of `Chymyst`

- Emitted molecules such as `c(123)` are _not_ Scala values.
Emitted molecules cannot be stored in a data structure or passed as arguments to functions.
The programmer has no direct access to the molecules in the soup, apart from being able to emit them.
But emitters _are_ ordinary, locally defined Scala values and can be manipulated as any other Scala values.
Emitters are functions whose `apply` method has the side effect of emitting a new copy of a molecule into the soup.
- Emitters are local values of type `B[T, R]` or `M[T]`. Both types extend the trait `MolEmitter` as well as the type `T ⇒ R`.
Blocking molecule emitters are of type `B[T, R]`, non-blocking of type `M[T]`.
- Reactions are local values of type `Reaction`. Reactions are created using the function `go` with the syntax `go { case ... ⇒ ... }`.
- Only one `case` clause can be used in each reaction. It is an error to use several `case` clauses,
or case clauses that do not match on input molecules, such as `go { case x ⇒ }`.
The only exception is "static reactions" described later.
- Reaction sites are immutable values of type `ReactionSite`. These values are not visible to the user: they are created in a closed scope by the `site(...)` call. The `site(...)` call activates all the reactions at that reaction site.
- Molecule emitters are immutable after all reactions have been activated where these molecules are used as inputs.
- When emitted into the soup, molecules gather at their reaction sites. Reaction sites proceed by first deciding which input molecules can be consumed by some reactions; this decision involves the chemical sorts of the molecules as well as any pattern matching and guard conditions that depend on molecule values.
When suitable input molecules are found and a reaction is chosen, the input molecules are atomically removed from the soup, and the reaction body is executed.
- The reaction body can emit one or more new molecules into the soup.
The code can emit new molecules into the soup at any time and from any code, not only inside a reaction body.
- When enough input molecules are present at a reaction site so that several alternative reactions can start, is not possible to decide which reactions will proceed first, or which molecules will be consumed first.
It is also not possible to know at what time reactions will start.
Reactions and molecules do not have priorities and are not ordered in the soup.
When determinism is required, it is the responsibility of the programmer to define the chemical laws such that the behavior of the program is deterministic.
(This is always possible!)
- All reactions that share some _input_ molecule must be defined within the same reaction site.
Reactions that share no input molecules can (and should) be defined in separate reaction sites.


## Chemical designations of molecules vs. molecule names vs. local variable names 

Each molecule has a name — a string that is used in error messages and for debugging.
The molecule's name must be specified when creating a new molecule emitter:

```scala
val counter = new M[Int]("counter")

```

Most often, we would like the molecule's name to be the same as the name of the local variable, such as `counter`, that holds the emitter.
When using the `m` macro, specifying names in this way becomes automatic:

```scala
val counter = m[Int]
counter.name == "counter" // true

```

Each molecule also has a specific chemical designation, such as `sum`, `counter`, and so on.
These chemical designations are not actually strings `"sum"` or `"counter"`.
The names of the local variables are chosen purely for the programmer's convenience.

Rather, the chemical designations are the _object identities_ of the molecule emitters.
We could define an alias for a molecule emitter, for example like this:

```scala
val counter = m[Int]
val q = counter

```

This code will copy the molecule emitter `counter` into another local value `q`.
However, this does not change the chemical designation of the molecule, because `q` will be a reference to the same JVM object as `counter`.
The emitter `q` will emit the same molecules as `counter`; that is, molecules emitted with `q(...)` will react in the same way and in the same reactions as molecules emitted with `counter(...)`.

(This is similar to how chemical substances are named in ordinary language.
For example, `NaCl`, "salt" and "sodium hydrochloride" are alias names for the same chemical substance.)

The chemical designation of the molecule specifies two aspects of the concurrent program:

- what other molecules (i.e. what other chemical designations) are required to start a reaction with this molecule, and at which reaction site;
- what computation will be performed when this molecule and all the other required input molecules are available.

When a new reaction site is defined, certain molecules become bound to that site.
(These molecules are the inputs of reactions defined at the new site.)
If a reaction site is defined within a local scope of a function, new molecule emitters and a new reaction site will be created _every time_ the function is called.
Since molecules internally hold a reference to their reaction site, each function call will create _chemically unique_ new molecules,
in the sense that these molecules will react only with other molecules defined in the same function call.

As an example, consider a function that defines a simple reaction like this:

```scala
def makeLabeledReaction() = {
  val begin = m[Unit]
  val label = m[String]
  
  site(
    go { case begin(_) + label(labelString) ⇒ println(labelString) }
  )
  
  (begin, label)
}

// Let's use this function now.

val (begin1, label1) = makeLabeledReaction() // first call
val (begin2, label2) = makeLabeledReaction() // second call

```

What is the difference between the new molecule emitters `begin1` and `begin2`?
Both `begin1` and `begin2` have the name `"begin"`, and they are of the same type `M[Unit]`.
However, they are _chemically_ different: `begin1` will react only with `label1`, and `begin2` only with `label2`.
The molecules `begin1` and `label1` are bound to the reaction site created by the first call to `makeLabeledReaction()`, and this reaction site is different from that created by the second call to `makeLabeledReaction()`.

For example, suppose we emit `label1("abc")` and `begin2()`.
We have emitted a molecule named `"label"` and a molecule named `"begin"`.
However, these molecules will not start any reactions, because they are bound to different reaction sites.

```scala
label1("abc") + begin2() // no reaction started!

```

After running this code, 
the molecule `label1("abc")` will be waiting at the first reaction site for its reaction partner, `begin1()`,
while the molecule `begin2()` will be waiting at the second reaction site for its reaction partner, `label2(...)`.
If we now emit, say, `label2("abc")`, the reaction with the molecule `begin2()` will start and print `"abc"`.

```scala
label1("abc") + begin2() // no reaction started!
label2("abc") // reaction with begin2() starts and prints "abc"

```

We see that `begin1` and `begin2` have different chemical designations because they enter different reactions.
This is so despite the fact that both `begin1` and `begin2` are defined by the same code in the function `makeLabeledReaction()`.
Since the code creates a new emitter value every time, the JVM object identities of `begin1` and `begin2` are different.

The chemical designations are independent of molecule names and of the variable names used in the code.
For instance, we could (for whatever reason) create aliases for `label2` and `begin2` and write code like this:

```scala
val (begin1, label1) = makeLabeledReaction() // first call
val (begin2, label2) = makeLabeledReaction() // second call
val (x, y, p, q) = (begin1, label1, begin2, label2) // make aliases

y("abc") + p() // Same as label1("abc") + begin2() — no reaction started!
q("abc") // Same as label2("abc") — reaction starts and prints "abc"

```

In this example, the values `x` and `begin1` refer to the same molecule emitter, thus they have the same chemical designation.
For this reason, the calls to `x()` and `begin1()` will emit copies of the same molecule.
As we already discussed, the molecule emitted by `x()` will have a different chemical designation from that emitted by `begin2()`.

In practice, it is of course advisable to choose meaningful local variable names.

To summarize:

- each molecule has a chemical designation, a reaction site to which the molecule is bound, a name, and a molecule emitter (usually assigned to a local variable)
- the chemical reactions started by molecules depend only on their chemical designations, not on names
- the molecule's name is only used in debugging messages
- the names of local variables are only for the programmer's convenience
- reaction sites defined in a local scope are new and unique for each time a new local scope is created
- molecules defined in a new local scope will have a new, unique chemical designation and will be bound to the new unique reaction site  

## The type matrix of molecule emission

Let us consider what _could_ theoretically happen when we call an emitter.

The emitter call can be either blocking or non-blocking, and it could return a value or return no value.
Let us write down all possible combinations of emitter call types as a “type matrix”.

To use a concrete example, we assume that `c` is a non-blocking emitter of type `M[Int]` and `f` is a blocking emitter of type `B[Unit, Int]`.

| | blocking emitter | non-blocking emitter |
|---|---|---|
| value is returned| `val x: Int = f()` | ? |
| no value returned | ? | `c(123)` // side effect |

So far, we have seen that blocking emitters return a value, while non-blocking emitters don't.
There are two more combinations that are not yet used:

- a blocking emitter that does not return a value
- a non-blocking emitter that returns a value

The `Chymyst` library implements both of these possibilities as special features:

- a blocking emitter can _time out_ on its call and fail to return a value;
- some non-blocking emitters have a “volatile reader” (see below) that provides read-only access to the last known value of the molecule.

With these additional features, the type matrix of emission is complete:

| | blocking emitter | non-blocking emitter |
|---|---|---|
| value is returned: | `val x: Int = f()` | `val x: Int = c.volatileValue` |
| no value returned: | (timeout was reached) | `c(123)` // side effect |

We will now describe these features in more detail.

### Timeouts for blocking emitters

By default, a blocking emitter will emit a new molecule and block until a reply action is performed for that molecule by some reaction.
If no reaction can be started that consumes the blocking molecule, its emitter will block and wait indefinitely.

`Chymyst` allows us to limit the waiting time to a fixed timeout value.
Timeouts are implemented by the method `timeout()()` on the blocking emitter:

```scala
val f = b[Unit, Int]
// create a reaction site involving `f` and other molecules:
site(...)

// call the emitter `f` with 200ms timeout:
val x: Option[Int] = f.timeout()(200 millis)

```

The first argument of the `timeout()()` method is the value carried by the emitted molecule.
In this example, this value is empty since `f` has type `B[Unit, Int]`.
The second argument of the `timeout()()` method is the duration of the delay.

The `timeout()()` method returns a value of type `Option[R]`, where `R` is the type of the blocking molecule's reply value.
In the code above, if the emitter received a reply value `v` before the timeout expired then the value of `x` will become `Some(v)`.

If the emitter times out before a reply action is performed, the value of `x` will be `None` and the blocking molecule `f()` will be _removed_ from the soup.
If the timeout occurred because no reaction started with `f()`, which is the usual reason, the removal of `f()` makes sense because no further reactions should try to consume `f()` and reply.

A less frequent situation is when a reaction already started, consuming `f()` and is about to reply to `f()`, but the waiting process just happened to time out at that very moment.
In that case, sending a reply to `f()` will have no effect.

Is the timeout feature required?
The timeout functionality can be simulated, in principle, using the “First Reply” construction.
However, this construction is cumbersome and will sometimes leave a thread blocked forever, which is undesirable from the implementation point of view.
For this reason, `Chymyst` implements the timeout functionality as a special primitive feature available for blocking molecules.

In some cases, the reaction that sends a reply to a blocking molecule needs to know whether the waiting process has already timed out.
For this purpose, `Chymyst` defines the reply emitters as functions `R ⇒ Boolean`.
The return value is `true` if the waiting process has received the reply, and `false` otherwise.

### Static molecules

It is often useful to ensure that exactly one copy of a certain molecule is initially present in the soup, and that no further copies can be emitted unless one is first consumed.
Such molecules are called **static**.
A static molecule `s` must have reactions only of the form `s + a + b + ... → s + c + d + ...`, — that is, reactions that consume a single copy of `s` and then also emit a single copy of `s`.

An example of a static molecule is the “asynchronous counter” molecule `c()` with reactions that we have seen before:

```scala
go { case c(x) + d(_) ⇒ c(x - 1) }
go { case c(x) + i(_) ⇒ c(x + 1) }
go { case c(x) + f(_, r) ⇒ c(x) + r(x) }

```

These reactions treat `c()` as a static molecule because they first consume and then emit a single copy of `c()`.

Static molecules are a frequently used pattern in chemical machine programming.
`Chymyst` provides special features for static molecules:

- Only non-blocking molecules can be declared as static.
- Static molecules are emitted directly from a reaction site definition, by special static reactions that run only once.
In this way, static molecules are guaranteed to be emitted once and only once, before any other reactions are run and before any molecules are emitted to that reaction site.
- Static molecules have “volatile readers”.

In order to declare a molecule as static, the users of `Chymyst` must write a reaction that has no input molecules but emits some output molecules.
Such reactions are automatically recognized by `Chymyst` as **static reactions**:

```scala
site (
// This static reaction declares a, c, and q to be static molecules and emits them.
    go { case _ ⇒ a(1) + c(123) + q() },
// Now define some more reactions that consume a, c, and q.
    go { case a(x) + ... ⇒ ???; a(y) } // etc.
)

```

The reaction `go { case _ ⇒ a(1) + c(123) + q() }`
emits three output molecules `a()`, `c()`, and `q()` but has a wildcard instead of input molecules.
`Chymyst` detects this and marks the reaction as static.
The output molecules are also declared as static.

A reaction site can have several static reactions.
Each non-blocking output molecule of each static reaction is automatically declared a **static molecule**.

The reaction sites will run their static reactions only once, at the time of the `site(...)` call itself, and on the same thread that calls `site(...)`.
At that time, the reaction site still has no molecules present.
Static molecules will be the first ones emitted into that reaction site.

#### Constraints on using static molecules

Declaring a molecule as static can be a useful tool for avoiding errors in chemical machine programs because the usage of static molecules is tightly constrained:

- A static molecule can be emitted only by a reaction that consumes it, or by the static reaction that defines it.
- A reaction may not consume more than one copy of a static molecule.
- A reaction that consumes a static molecule must also have some non-static input molecules.
- It is an error if a reaction consumes a static molecule but does not emit it back into the soup, or emits it more than once.
- It is also an error if a reaction emits a static molecule it did not consume, or if any other code emits additional copies of the static molecule at any time.
- A reaction may not emit static molecules from within a loop or within function calls.

These restrictions are intended to maintain the semantics of static molecules.
Application code that violates these restrictions will cause an "early" run-time error - that is, an exception thrown by the `site()` call before any reactions can run at that reaction site.

### Volatile readers for static molecules

When a static molecule exists only as a single copy, its value works effectively as a mutable cell:
the value can be modified by reactions, but the cell cannot be destroyed.
So it appears useful to have a read-only access to the value in the cell.

This is implemented as a **volatile reader** — a function of type `⇒ T` that fetches the value carried by that static molecule when it was most recently emitted.
Here is how volatile readers are used:

```scala
val c = m[Int]
site(
  go { case c(x) + incr(_) ⇒ c(x + 1) },
  go { case _ ⇒ c(0) } // emit `c(0)` and declare it as static
)

val readC: Int = c.volatileValue // initially returns 0

```

The volatile reader is thread-safe (can be used from any reaction without blocking any threads) because it provides a read-only access to the value carried by the molecule.

The value of a static molecule, viewed as a mutable cell, can be modified only by a reaction that consumes the molecule and then emits it back with a different value.
If the volatile reader is called while that reaction is being run, the reader will return the previous known value of the static molecule, which is probably going to become obsolete very shortly.
We call the volatile reader “volatile” for this reason: it returns a value that could change at any time.

The functionality of a volatile reader is similar to an additional reaction with a blocking molecule `f` that reads the current value carried by `c`:

```scala
go { case c(x) + f(_, reply) ⇒ c(x) + reply(x) }

```

Calling `f()` returns the current value carried by `c()`, just like the volatile reader does.
However, the call `f()` may block for an unknown time if `c()` has been consumed by a long-running reaction, or if the reaction site happens to be busy with other computations.
Even if `c()` is immediately available in the soup, running a new reaction requires an extra scheduling operation.
Volatile readers provide fast read-only access to values carried by static molecules.

This feature is restricted to static molecules because it appears to be useless if we read the last emitted value of a molecule that has many different copies emitted in the soup.

## Exercise: concurrent divide-and-conquer with cancellation

Implement a (blocking) function that finds the smallest element in an integer array, using a concurrent divide-and-conquer method.
If the smallest element turns out to be negative, the computation should be aborted as early as possible, and `0` should be returned as the final result.

Use a static molecule with a volatile reader to signal that the computation needs to be aborted early.
