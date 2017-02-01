<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Molecules and emitters, in depth

## Molecule names

For debugging purposes, molecules in `Chymyst` have names.
These names have no effect on any concurrent computations.
For instance, the runtime engine will not check that each molecule's name is not empty, or that the names of different molecules are different.
Molecule names are used only for debugging: they are printed when logging reactions and reaction sites.

There are two ways of assigning a name to a molecule:

- specify a name explicitly, by using a class constructor;
- use the macros `m` and `b`.

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
So the value `c` itself is not a molecule in the soup.
The value `c` is a **molecule emitter**, -- that is, a function that, when called, will emit molecules of chemical sort `c` into the soup.
The result of evaluating the emitter call such as `c(123)` is a _side effect_ that emits the molecule of sort `c` with value `123`.

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

Molecules carrying `Unit` values can be emitted using the syntax such as `a()` rather than `a(())`.
This is provided as a syntactic convenience, equivalent to writing `a(())`.


### Blocking molecules

For a **blocking** molecule, the emitter call will block until a reaction can start that consumes that molecule.

A blocking emitter is defined like this,

```scala
val f = b[Int, String]

```

Now `f` is a blocking emitter that takes an `Int` value and returns a `String`.

Emitters for blocking molecules are values of type `B[T, R]`, which is a subtype of `Function1[T, R]`.
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
go { case c(x) + f(y, r) =>
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
- Emitters are local values of type `B[T, R]` or `M[T]`. Both types extend the abstract trait `Molecule` as well as the `Function1[T, R]` trait.
Blocking molecule emitters are of type `B[T, R]`, non-blocking of type `M[T]`.
- Reactions are local values of type `Reaction`. Reactions are created using the function `go` with the syntax `go { case ... => ... }`.
- Only one `case` clause can be used in each reaction. It is an error to use several `case` clauses, or case clauses that do not match on input molecules, such as `go { case x => }`.
- Reaction sites are immutable values of type `ReactionSite`. These values are not visible to the user: they are created in a closed scope by the `site(...)` call. The `site(...)` call activates all the reactions at that reaction site.
- Molecule emitters are immutable after all reactions have been activated where these molecules are used as inputs.
- Molecules emitted into the soup gather at their reaction site. Reaction sites proceed by first deciding which input molecules can be consumed by some reactions; this decision involves the chemical sorts of the molecules as well as any pattern matching and guard conditions that depend on molecule values.
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

Each molecule has a specific chemical designation, such as `sum`, `counter`, and so on.
These chemical designations are not actually strings `"sum"` or `"counter"`.
The names of the local variables and the molecule names are chosen purely for convenience.

Rather, the chemical designations are the _object identities_ of the molecule emitters.
We could define an alias for a molecule emitter, for example like this:

```scala
val counter = m[Int]
val q = counter

```

This code will copy the molecule emitter `counter` into another local value `q`.
However, this does not change the chemical designation of the molecule, because `q` will be a reference to the same object as `counter`.
The emitter `q` will emit the same molecules as `counter`; that is, molecules emitted with `q(...)` will react in the same way and in the same reactions as molecules emitted with `counter(...)`.

(This is similar to how chemical substances are named in ordinary language.
For example, `NaCl`, "salt" and "sodium hydrochloride" are alias names for the same chemical substance.)

The chemical designation of the molecule specifies two aspects of the concurrent program:

- what other molecules (i.e. what other chemical designations) are required to start a reaction with this molecule, and at which reaction site;
- what computation will be performed when all the required input molecules are available.

When a new reaction site is defined, new molecules bound to that site are defined as well.
(These molecules are inputs to reactions defined at the new site.)
If a reaction site is defined within a local scope of a function, a new reaction site will be created every time the function is called.
Since molecules internally hold a reference to their reaction site, each function call will create _chemically unique_ new molecules,
in the sense that these molecules will react only with other molecules defined in the same function call.

As an example, consider a function that defines a simple reaction like this:

```scala
def makeLabeledReaction() = {
  val begin = m[Unit]
  val label = m[String]
  
  site(
    go { case begin(_) + label(labelString) => println(labelString) }
  )
  
  (begin, label)
}

// Let's use this function now.

val (begin1, label1) = makeLabeledReaction() // first call
val (begin2, label2) = makeLabeledReaction() // second call

```

What is the difference between the new molecule emitters `begin1` and `begin2`?
Both `begin1` and `begin2` have the name `"begin"`, and they are of the same type.
However, they are _chemically_ different: `begin1` will react only with `label1`, and `begin2` only with `label2`.
The molecules `begin1` and `label1` are bound to the reaction site created by the first call to `makeLabeledReaction()`, and this reaction site is different from that created by the second call to `makeLabeledReaction()`.

For instance, suppose we call `label1("abc")` and `begin2()`.
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

We see that `begin1` and `begin2` have different chemical designations (because they enter different reactions), even though they are both defined by the same code inside the function `makeLabeledReaction`.

The chemical designations are independent of molecule names and of the variable names used in the code.
For instance, we could (for whatever reason) create aliases for `label2` and `begin2` and write code like this:

```scala
val (begin1, label1) = makeLabeledReaction() // first call
val (begin2, label2) = makeLabeledReaction() // second call
val (x, y, p, q) = (begin1, label1, begin2, label2) // make aliases

y("abc") + p() // Same as label1("abc") + begin2() - no reaction started!
q("abc") // Same as label2("abc") - reaction starts and prints "abc"

```

In this example, the values `x` and `begin1` are equal to the same molecule emitter, thus they have the same chemical designation.
For this reason, the calls to `x()` and `begin1()` will emit copies of the same molecule.
As we already discussed, the molecule emitted by `x()` will have a different chemical designation from that emitted by `begin2()`.

In practice, it is of course advisable to choose meaningful local variable names.
However, it is important to keep in mind that:

- each molecule has a chemical designation, a reaction site to which the molecule is bound, a name, and a molecule emitter (usually assigned to a local variable)
- the chemical reactions started by molecules depend only on their chemical designations, not on names
- the molecule's name is only used in debugging messages
- the names of local variables are only for the programmer's convenience
- reaction sites defined in a local scope are new and unique for each time a new local scope is created
- molecules defined in a new local scope will have a new, unique chemical designation and will be bound to the new unique reaction site  

## The type matrix of molecule emission

Let us consider what _could_ theoretically happen when we call an emitter.
The emitter call can be either blocking or non-blocking, and it could return a value or return no value.
Let us write down all possible combinations of these types of emitter calls as a “type matrix”.

For this example, we assume that `c` is a non-blocking emitter of type `M[Int]` and `f` is a blocking emitter of type `B[Unit, Int]`.

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
| no value returned: | timeout was reached | `c(123)` // side effect |

We will now describe these features in more detail.

### Timeouts for blocking emitters

By default, a blocking emitter will emit a new molecule and block until a reply action is performed for that molecule by a reaction that consumes that molecule.
If no reaction can be started that consumes the blocking molecule, its emitter will block and wait indefinitely.

`Chymyst` allows us to limit the waiting time to a fixed timeout value.
Timeouts are implemented by the method `timeout()()` on the blocking emitter:

```scala
val f = b[Unit, Int]
// write a reaction site involving `f` and other molecules:
site(...)

// call the emitter `f` with 200ms timeout:
val x: Option[Int] = f.timeout()(200 millis)

```

The first argument of the `timeout()()` method is the value carried by the emitted molecule.
In this example, this value is empty since `f` has type `B[Unit, Int]`.
The second argument of the `timeout()()` method is the duration of the delay.

The `timeout()()` method returns a value of type `Option[R]`, where `R` is the type of the reply value.
In the code above, if the emitter received a reply value `v` before the timeout expired then the value of `x` will become `Some(v)`.

If the emitter times out before a reply action is performed, the value of `x` will be set to `None`, and the blocking molecule `f()` will be _removed_ from the soup.
If the timeout occurred because no reaction started with `f()`, which is the usual reason, the removal of `f()` makes sense because no further reactions should try to consume `f()` and reply.

A less frequent situation is when a reaction already started, consuming `f()` and is about to reply to `f()`, but it just happens to time out at that moment.
In that case, the reply action to `f()` will have no effect.

Is the timeout feature required?
The timeout functionality can be simulated, for instance, using the “First Reply” construction.
However, this construction is cumbersome and will sometimes leave a thread blocked forever, which is undesirable from the implementation point of view.
For this reason, `Chymyst` implements the timeout functionality as a special primitive feature available for blocking molecules.

### Static molecules

Often it is necessary to ensure that exactly one copy of a certain molecule is initially present in the soup, and that no further copies can be emitted unless a copy is first consumed.
Such molecules are called **static**.
A static molecule `s` must have reactions only of the form `s() + ... => s() + ...`, -- that is, reactions that consume the single copy of `s` and then also emit a single copy of `s`.

An example of a static molecule is the “concurrent counter” molecule `c()`, with reactions that we have seen before:

```scala
c(x) + d(_) => c(x - 1)
c(x) + i(_) => c(x + 1)
c(x) + f(_, r) => c(x) + r(x)
```

These reactions treat `c()` as a static molecule because they first consume and then emit a single copy of `c`.

Static molecules are a frequently used pattern in chemical machine programming.
`Chymyst` provides special features for static molecules:

- Only non-blocking molecules can be declared as static.
- It is an error if a reaction consumes a static molecule but does not emit it back into the soup, or emits it more than once.
- It is also an error if a reaction emits a static molecule it did not consume, or if any other code emits additional copies of the static molecule at any time.
(However, this feature is easy to simulate with local scoping, which can prevent other code from having access to a molecule emitter.)
- Static molecules are emitted directly from the reaction site definition, by special **static reactions** (see below) that run only once.
In this way, static molecules are guaranteed to be emitted once and only once, before any other reactions are run and before any molecules are emitted to that reaction site.
- Static molecules have “volatile readers” (see below).

In order to declare a molecule as static, the users of `Chymyst` must write a reaction that has no input molecules but emits some output molecules:

```scala
site (
    // This is a static reaction that declares a, c, and q to be static molecules, and emits each of them.
    go { case _ => a(1) + c(123) + q() },
    // Now we need to define some more reactions that consume a, c, and q.
    go { case a(x) + ... => ??? } // etc.
)

```

Look at the reaction `go { case _ => a(1) + c(123) + q() }`.
This reaction emits three output molecules `a()`, `c()`, and `q()`, but has a wildcard instead of input molecules. 
Such reactions are automatically recognized by `Chymyst` as **static reactions**.
A static reaction emits some molecules into the soup and at the same time declares these molecules as static.

A reaction site can have one or more static reactions.
Each non-blocking output molecule of each static reaction will be automatically declared a **static molecule**.

The reaction sites will run their static reactions only once, at the time of the `site(...)` call itself, and on the same thread that calls `site(...)`.
At that time, the reaction site has no molecules present.
Static molecules will be the first ones emitted into the soup at that reaction site.

### Volatile readers for static molecules

When a static molecule exists only as a single copy, its value works effectively as a mutable cell:
the value can be modified by reactions, but the cell cannot be destroyed.
So it appears useful to have a read-only access to the value in the cell.

Accordingly, each static molecule has a **volatile reader** -- a function of type `=> T` that fetches the value carried by that static molecule when it was most recently emitted.
Here is how volatile readers are used:

```scala
val c = m[Int]
site(
  go { case c(x) + incr(_) => c(x + 1) },
  go { case _ => c(0) } // emit `c(0)` and declare it as static
)

val readC: Int = c.volatileValue // initially returns 0

```

The volatile reader is thread-safe (can be used from any reaction without blocking any threads) because it provides a read-only access to the value carried by the molecule.

The value of a static molecule, viewed as a mutable cell, can be modified only by a reaction that consumes the molecule and then emits it back with a different value.
If the volatile reader is called while that reaction is being run, the reader will return the previous known value of the static molecule, which is probably going to become obsolete very shortly.
I call the volatile reader “volatile” for this reason: it returns a value that could change at any time.

The functionality of a volatile reader is similar to an additional reaction with a blocking molecule `f` that reads the current value carried by `c`:

```scala
go { case c(x) + f(_, reply) => c(x) + reply(x) }

```

Calling `f()` returns the current value carried by `c()`, just like the volatile reader does.
However, the call `f()` may block for an unknown time if `c()` has been consumed by a long-running reaction.
Even if `c()` is immediately available in the soup, running this reaction requires an extra scheduling operation.
Volatile readers provide very fast read-only access to the values carried by static molecules.

This feature is restricted to static molecules because it appears to be useless if we could get the last emitted value of a molecule that has many different copies emitted in the soup.
