<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# From actors to reactions: The chemical machine explained through the Actor model

Many Scala developers are already familiar with the Actor model through the [Akka library](https://github.com/akka/akka).
In this brief chapter, I outline how these readers can easily grasp the chemical machine paradigm by capitalizing on the knowledge of the Actor model.

In the Actor model, an actor receives messages and reacts to them by running a computation.
An actor-based program declares several actors, defines the computations for them, stores references to the actors, and starts sending messages to some of the actors.
Messages are sent either synchronously or asynchronously, enabling communication between different concurrent actors. 

The chemical machine paradigm is in many ways similar to the Actor model.
A chemical program also runs light-weight concurrent processes,
which we may think of as “chemical actors”, that communicate by sending data to each other.
The chemical machine paradigm departs from the Actor model in two major ways: 

1. Chemical actors are created automatically by the runtime system whenever necessary. User's code does not create or manage specific instances of actors.
2. Chemical actors may wait for several _different_ messages (i.e. messages sent to different mailboxes). Once all the required input messages are available, a chemical actor will consume them all at the same time, atomically.

If we examine these requirements and determine what should logically follow from them, we will arrive at the chemical machine paradigm.

## Managing actors automatically

The first requirement means that the chemical machine runtime will automatically instantiate and run a chemical actor's computation whenever some process sends a relevant input message.
If several messages are sent to the same chemical actor, the automatic instantiation mechanism can do nothing else than to create a copy of _the same_ actor each time.
It follows that a chemical actor must be _stateless_: it cannot carry any mutable state.

Since chemical actors are immutable, the user's code does not need to handle an actor's “lifecycle” any more.
There is no need to persist a chemical actor's state or restore it in case of a crash.
The user's code merely needs to _define the computation_ that a chemical actor will perform after consuming a message.

Implementing this functionality will allow us to write pseudo-code like this,

```scala
// Pseudo-code!
val c1 = go { x: Int ⇒ f(x) }
c1 ! 123

```

The computation under the `go{}` signifies a chemical actor that receives an `Int` value and performs some processing on it.
The chemical actor will be automatically instantiated and run, whenever a message is sent to `c1`.

The function `f(x)` may run a side effect and/or send further messages to other actors.
Chemical actors are stateless, so defining an actor means merely to specify a function, such as `{ x: Int ⇒ f(x) }`.

The value `c1` must be used to send messages to the chemical actor in our example.
But `c1` is not a reference to a specific instance of an actor, or to a specific instance of a computation or a thread.
Since all we do with `c1` is send messages through it, it follows that `c1` is a reference to a specific _mailbox_.
Our intention is that the runtime will start a new instance of the computation `{ x: Int ⇒ f(x) }` whenever that mailbox receives new messages.

Since chemical actors are stateless, they can only perform computations that are _functions of_ the incoming messages.
So, if we send several copies of the same message, the chemical machine will run several instances of the same computation.
If these computations are _pure_ functions, it is safe to run all these computations in parallel.
Therefore, it makes sense for the chemical machine to decide _automatically_ that these computations can be parallelized,
and to create and run several chemical actors at the same time.

As an example, consider what would happen if we quickly send many messages to the same mailbox:

```scala
// Pseudo-code!
val c1 = go { x: Int ⇒ f(x) }
(1 to 100).foreach { c1 ! _ }

```

We expect that 100 different instances of the same computation `{ x: Int ⇒ f(x) }` should be run, with different values of `x`.

The chemical machine assumes that it may run any number of instances of the computation `{ x: Int ⇒ f(x) }` concurrently.
The runtime engine will automatically adjust the degree of parallelism depending on the available number of CPU cores.

The first requirement has logically lead us to having stateless, immutable actors with automatic parallelism.
We have made the first step towards the chemical machine paradigm.

## Waiting for several messages at once

Compared with ordinary actors that can carry mutable state, what functionality needs to be added to chemical actors to make them equally expressive?

A chemical actor may be seen as a stateless, automatically concurrently running function whose argument is an incoming message.
Ordinary actors that carry mutable state can be also seen as functions with two arguments: the incoming message and the previous state.
Therefore, chemical actors that are stateless but can take _two incoming messages at once_ will be equivalent to ordinary actors with state.

In this way, we have logically arrived at the requirement that chemical actors should be able to wait for and consume several messages at once.  
How can we implement this requirement?

In our previous example, the pseudo-code `go { x: Int ⇒ f(x) }` was merely a declarative description of what needs to be done with messages sent to the mailbox `c1`.
To express this semantics more clearly, let us change our pseudo-code notation to

```scala
// Pseudo-code!
go { x: Int from c1 ⇒ f(x) }
c1 ! 123

```

It is clear that different chemical actors can use different input mailboxes, for example:

```scala
// Pseudo-code!
go { x: Int from c1 ⇒ f(x) }
go { x: Int from d1 ⇒ g(x) }
c1 ! 123
d1 ! 456

```

A chemical actor that waits for two messages at once can now be represented by pseudo-code like this,

```scala
// Pseudo-code!
go { x: Int from c1, y: String from c2 ⇒ h(x, y) }
c1 ! 123
c2 ! "abc"

```

The two messages carry data of different types; the two mailboxes are `c1` and `c2` respectively.
The chemical actor starts only after _both_ messages have been sent, and consumes both messages atomically.

It also follows from the atomicity requirement that we may define several computations that jointly contend on input messages:

```scala
// Pseudo-code!
go { x: Int from c1, y: String from c2 ⇒ h(x, y) }
go { x: Int from c1, z: Unit from c3   ⇒ k(x) }
c1 ! 123
c2 ! "abc"

```

If messages are present in `c1` but not in `c2` or `c3`, no computations will be started until some process emits messages to either `c2` or `c3`.
Each of the two chemical actors can start only if it can consume one message from `c1` and one message from another mailbox.

If there is exactly one message in each of the three mailboxes `c1`, `c2`, `c3`, then any one of the two chemical actors might start.
The runtime engine must make a non-deterministic choice to start one of them.
Suppose, for instance, that the second chemical actor starts; it will then atomically consume two messages -- one message from `c1` and one from `c3`.
Since consuming the only message from `c1` will make the mailbox `c1` empty, the first chemical actor will not be able to start.
In this way, the program expresses the contention of several processes on a shared resource.

This concludes the second and final step towards the chemical machine paradigm.

It remains to use the Scala syntax instead of pseudo-code.
In Scala, we need to declare message types explicitly using the `m` macro,
and to register reactions with the runtime engine as a separate step using the `site()` call.
The syntax used by `Chymyst` to represent the above pseudo-code looks like this:

```scala
val c1 = m[Int]
val c2 = m[String]
val c3 = m[Unit]
site(
  go { case c1(x) + c2(y) ⇒ h(x, y) }
  go { case c1(x) + c3(_) ⇒ k(x) }
)
c1(123)
c2("abc")

```

Here, `m[Int]` creates a new mailbox reference (called "molecule emitter") with values of type `Int`.
The function calls `c1(123)` and `c2("abc")` emit messages (called "molecules") to their respective mailboxes.

## Unordered mailboxes

Since `Chymyst` uses the Scala partial function syntax to define chemical actors, the definition may contain arbitrary guard conditions, for example:

```scala
val c1 = m[Int]
val c2 = m[String]
val c3 = m[Unit]
site(
  go { case c1(x) + c2(y) if x > y.length ⇒ h(x, y) }
  go { case c1(x) + c3(_) if x == 0 ⇒ k(x) }
)
c1(123)
c2("abc")

```

A guard condition allows a computation to start only when the values of input messages satisfy a given predicate.
Depending on what messages are present in any given time in various mailboxes, some messages may satisfy the predicate while others do not.
In order to be able to make progress in such situations, a chemical actor may need to consume messages out of order. 

For this reason, messages in certain mailboxes may need to be kept as an unordered multi-set or "bag", rather than as an ordered queue.
This usually only applies to mailboxes that participate in computations with complicated guard conditions.
`Chymyst` automatically detects these situations and decides which mailboxes need to be unordered.
Since all the chemical actors are defined up front, this analysis can be performed before any computations are started.

## Conclusions

As we have just seen, the chemical machine paradigm is a radical departure from the Actor model.

Whenever there are sufficiently many input messages available for processing, the runtime engine may automatically instantiate several concurrent copies of the same chemical actor, and allow these copies to consume the input messages concurrently and in parallel.
This is the main method for achieving parallelism in the chemical paradigm. The runtime engine is in the best position to balance the CPU load over low-level threads.
The user's application code does not need to specify how many parallel processes to run at any given time.

Input message contention is used in the chemical machine paradigm as a general mechanism for synchronization and mutual exclusion. (In the Actor model, these features are implemented by creating a fixed number of actor instances that alone can consume certain messages.) Since the runtime engine will arbitrarily decide which actor to run, contention on input messages will result in a certain degree of indeterminism. This is quite similar to the indeterminism in the usual models of concurrent programming. For example, mutual exclusion allows the programmer to implement safe exclusive access to a resource for any number of concurrent processes, but the order of access among the contending processes remains undetermined.
It is up to the programmer to ensure that the final results of the computation remain deterministic (i.e. that there are no race conditions).
The chemical machine forces the programmer to face the indeterminism where it is unavoidable, but at the same time frees the programmer from the low-level bookkeeping associated with managing parallelism explicitly. 

Since chemical actors are stateless and instantiated automatically on demand, the application code does not need to manipulate references to actor instances, which is error-prone.
(For example, books on Akka routinely warn against capturing `sender()` in a `Future`, which may yield an incorrect actor reference when the `Future` is resolved.)
With the chemical machine, the application code does not need to implement actor lifecycle management, actor hierarchies, backup and recovery of actors' internal state, or handle the special “dead letter” actor. This removes a significant amount of complexity from the architecture of concurrent applications.

Working in the chemical machine paradigm is more declarative, more high-level, and closer to being purely functional than in the Actor model.
Since all data resides on immutable messages rather than in mutable state, program design becomes data-driven as the programmer can focus on
assigning computations to messages, rather than on error-prone thinking in terms of synchronized parallel processes. 

In the rest of the book, "chemical actors" are called **reactions**, "messages" are **molecules**, and "mailbox references" are **molecule emitters**. 
