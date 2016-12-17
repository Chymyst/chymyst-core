<link href="{{ site.github.url }}/tables.css" rel="stylesheet">

# Molecules and injectors, in depth

## Molecule names

For debugging purposes, molecules in `JoinRun` can have names.
These names have no effect on any concurrent computations.
For instance, the runtime engine will not check that each molecule is assigned a name, or that the names for different molecule sorts are different.
Molecule names are used only for debugging: they are printed when logging reactions and join definitions.

There are two ways of assigning a name to a molecule:
- specify the name explicitly, by using a class constructor;
- use the macros `m` and `b`.

Here is an example of defining injectors using explicit class constructors and molecule names:

```scala
val counter = new M[Int]("counter")
val fetch = new B[Unit, Int]("fetch")

```

This code is completely equivalent to the shorter code written using macros:

```scala
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._

val counter = m[Int]
val fetch = b[Unit, Int]

```

These macros can read the names `"counter"` and `"fetch"` from the surrounding code context.
This functionality is intended as a syntactic convenience.

Each molecule injector as a `toString` method.
This method will return the molecule's name if it was assigned.
For blocking molecules, the molecule's name is followed by `"/B"`.

```scala
val x = new M[Int]("counter")
val y = new B[Unit, Int]("fetch")

x.toString // returns “counter"
y.toString // returns “fetch/B"

```

## Remarks about the semantics of JoinRun

- Injected molecules are _not_ Scala values.
Injected molecules cannot be, say, stored in a data structure or passed as arguments to functions.
The programmer has no direct access to the molecules in the soup, apart from being able to inject them.
But injectors _are_ ordinary, locally defined Scala values and can be manipulated as any other Scala values.
- Injectors are local values of class `B` or `M`, which both extend the abstract class `Molecule`.
Blocking molecule injectors are of class `B`, non-blocking of class `M`.
- Reactions are local values of class `Reaction`. Reactions are created using the function `run { case ... => ... }`.
- Only one `case` clause can be used in each reaction.
- Join definitions are values of class `JoinDefinition`. These values are not visible to the user: they are created in a closed scope by the `join` function.
- Join definitions are immutable once written.
- Molecule injectors are immutable after all reactions have been written where these molecules are used.
- Reactions proceed by first deciding which molecules can be used as inputs to some reaction; these molecules are then atomically removed from the soup, and the reaction body is executed.
Typically, the reaction body will inject new molecules into the soup.
- We can inject new molecules into the soup at any time and from any code (not only inside a reaction body).
- It is not possible to decide which reactions will proceed first, or which molecules will be consumed first, when the chemistry allows several possibilities. It is also not possible to know at what time reactions will start. Reactions and molecules do not have priorities and are not ordered in the soup. It is the responsibility of the programmer to define the chemical laws appropriately so that the behavior of the program is deterministic when determinism is required. (This is always possible!)

- All reactions that share some _input_ molecule must be defined in the same join definition.
Reactions that share no input molecules can (and should) be defined in separate join definitions.


## Molecules and molecule injectors

Molecules are injected into the “chemical soup” using the syntax such as `c(123)`. Here, `c` is a value we define using a construction such as

```scala
val c = m[Int]

```

Any molecule injected in the soup must carry a value.
So the value `c` itself is not a molecule in the soup.
The value `c` is a **molecule injector**, - that is, a function that, when called, will inject molecules of sort `c` into the soup.
The result of calling the injector when evaluating `c(123)` is a _side-effect_ that injects the molecule of sort `c` with value `123` into the soup.

As defined above, `c` is a non-blocking sort of molecule, so the call `c(123)` is non-blocking -- it does not wait for any reactions involving `c(123)` to start.
Calling `c(123)` will immediately return a `Unit` value.

The non-blocking injector `c` has type `M[Int]` and can be also created directly using the class constructor:

```scala
val c = new M[Int]("c")

```

For a blocking molecule, the injection call will block until a reaction can start that consumes that molecule.

A blocking injector is defined like this,

```scala
val f = b[Int, String]

```

Now `f` is an injector that takes an `Int` value and returns a `String`.

Injectors for blocking molecules are essentially functions: their type is `B[T, R]`, which extends `Function1[T, R]`.
The injector `f` could be equivalently defined by

```scala
val f = new B[Int, String]("f")

```

Once `f` is defined like this, an injection call such as

```scala
val x = f(123)

```

will inject a molecule of sort `f` with value `123` into the soup.

The calling process in `f(123)` will wait until some reaction consumes this molecule and executes a “reply action” with a `String` value.
Only after the reaction body executes the “reply action”, the `x` will be assigned to that string value, and the calling process will become unblocked and will continue its computations.

## The injection type matrix

Let us consider what _could_ theoretically happen when we call an injector function.
The injector call can be either blocking or non-blocking, and it could return a value or return no value.
Let us write down all possible combinations of these types of injector calls as a “type matrix”.

For this example, we assume that `c` is a non-blocking injector of type `M[Int]` and `f` is a blocking injector of type `B[Unit, Int]`.

| | blocking injector | non-blocking injector |
|---|---|---|
| value is returned| `val x: Int = f()` | ? |
| no value returned | ? | `c(123)` // side effect |

So far, we have seen that blocking injectors return a value, while non-blocking injectors don't.
There are two more combinations that are not yet used:

- a blocking injector that does not return a value
- a non-blocking injector that returns a value

The `JoinRun` library implements both of these possibilities as special features:

- a blocking injector can time out on its call and fail to return a value;
- a non-blocking injector can return a “volatile reader” (see below) that has read-only access to the last known value of the molecule.

With these additional features, the type matrix of injection is complete:

| | blocking injector | non-blocking injector |
|---|---|---|
| value is returned: | `val x: Int = f()` | `val x: Int = c.volatileValue` |
| no value returned: | timeout was reached | `c(123)` // side effect |

### Timeouts for blocking injectors

By default, a blocking injector call will block until a new reaction is started that consumes the blocking molecule and performs the reply action on that molecule.
If no reaction can be started that consumes the blocking molecule, the injector call will block and wait indefinitely.
It is often useful to limit the waiting time to a fixed timeout value.
`JoinRun` implements the timeout as an additional argument to the blocking injector:

```scala
val f = b[Unit, Int]
// write a join definition involving `f` and other molecules:
join(...)

// call `f` with 200ms timeout:
val x: Option[Int] = f(timeout = 200 millis)()

```

If the injector call to `f` timed out without any reply action, the value of `x` will be `None`, and the blocking molecule `f()` will be removed from the soup (so that reactions will not start with it and attempt to reply).
If a reaction already started and attempts to reply with a blocking molecule that already timed out, the reply action will have no effect.

If the injector the call to `f()` succeeded and returned a reply value `r`, the value of `x` will be `Some(r)`.

The timeout functionality can be implemented, in principle, using the “First Reply” construction.
However, this construction is cumbersome and will sometimes leave a thread blocked forever, which is undesirable from the implementation point of view.

### Singleton molecules

Often it is necessary to ensure that a certain molecule is present in the soup at most once.
Such molecules are called **singletons**.
Singleton molecules `s` must have reactions of the form `s + ... => s + ...`, -- that is, reactions that consume a single copy of `s` and then output a single copy of `s`.

An example of a singleton is the concurrent counter molecule `c`, with reactions

```scala
c(x) + d(_) => c(x-1)
c(x) + i(_) => c(x+1)
c(x) + f(_, r) => c(x) + r(x)
```

These reactions treat `c` as a singleton because they first consume and then output a single copy of `c`.

`JoinRun` provides special features for singleton molecules:

- Only non-blocking molecules can be declared as singletons.
- It is an error if a reaction consumes a singleton but does not inject it back into the soup, or injects it more than once.
- It is also an error if a reaction injects a singleton it did not consume, or if any other code injects additional copies of the singleton at any time. (However, local scoping can prevent other code from having access to a singleton injector.)
- Singleton molecules are injected directly from the join definition.
In this way, singleton molecules are guaranteed to be injected once and only once.
- Singleton molecules have “volatile readers”.

In order to declare a molecule as a singleton, the users of `JoinRun` can write a reaction that has no input molecules:

```scala
join (
    // inject and declare a, c, and q to be singletons
    run { case _ => a(1) + c(123) + q() }
    // now define some reactions that consume a, c, and q
)

```

Each non-blocking output molecule of such a reaction must be injected only once and is then declared to be a singleton molecule.

The join definitions will run their singleton reactions once and only once, at the time of the `join(...)` call itself.

### Volatile readers for singleton molecules

Each singleton molecule has a **volatile reader** -- a function of type `=> T` that fetches the most recently injected value carried by that singleton molecule.

```scala
val c = m[Int]
join(
  run { case c(x) + incr(_) => c(x+1) },
  run { case _ => c(0) } // inject `c(0)` and declare it a singleton
)

val readC: Int = c.volatileValue // initially returns 0

```

The volatile reader is thread-safe (can be used from any reaction without blocking any threads) because it provides a read-only access to the value carried by the molecule.
The value of a singleton molecule can be modified only by a reaction that consumes the singleton and then injects it back with a different value.
If the volatile reader is called while that reaction is being run, the reader will return the previous known value of the singleton, which is probably going to become obsolete very shortly.
I call the volatile reader “volatile” for this reason.

The functionality of a volatile reader is equivalent to an additional reaction with a blocking molecule `f` that will read the value of `c`, such as

```scala
run { case c(x) + f(_, reply) => c(x) + reply(x) }

```

Calling `f()` returns the current value carried by `c`.
However, the call `f()` may block for an unknown time and requires an extra scheduling operation.
A volatile reader provides very fast read-only access to the value of a singleton molecule.

The reason this feature is restricted to singletons is that it makes no sense to ask the molecule injector `c` for the current value of its molecule if there are a thousand different copies of `c` injected in the soup.
