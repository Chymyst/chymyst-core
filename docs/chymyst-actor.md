<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# From actors to reactions: The chemical machine explained through the Actor model

Many Scala developers interested in concurrent programming are already familiar with the Actor model. In this brief chapter, I outline how the chemical machine paradigm can be introduced to those readers.

In the Actor model, an actor receives messages and reacts to them by running a computation. An actor-based program declares several actors, defines the computations for them, stores references to the actors, and starts sending messages to some of the actors. Messages are sent either synchronously or asynchronously, enabling communication between different concurrent actors. 

The chemical machine paradigm is in certain ways similar to the Actor model. A chemical program also consists of concurrent processes, or “chemical actors”, that communicate by sending messages. The chemical machine paradigm departs from the Actor model in two major ways: 

1. Chemical actors are automatically started and stopped; the user's code only sends messages and no longer needs to work with actor references.
2. Chemical actors may wait for and consume several messages at once.

If we examine these requirements and determine what should logically follow from them, we will arrive at the chemical machine paradigm.

The first requirement means that chemical actors are not created explicitly by the user's program. Instead, the chemical machine runtime will automatically instantiate and run a chemical actor whenever an input message is available for consumption. A chemical actor will automatically stop and get deleted when its computation is finished. Therefore, the user's code now does not create an instance of an actor but merely defines the computation that an auto-created actor will perform upon receipt of a message. As a consequence, a chemical actor must be _stateless_ and only perform computations on its input message values.

Implementing this functionality will allow us to write pseudo-code like this,

```scala
val c1 = go { x: Int ⇒ ... }
c1 ! 123

```

The computation labeled as `c1` receives a message with an `Int` value and performs some processing on it. The computation will be instantiated and run concurrently, whenever a message is sent. In this way, we made the first step towards the full chemical machine paradigm. 

What should happen if we send several messages quickly?

```scala
val c1 = go { x: Int ⇒ ... }
(1 to 100).foreach{c1 ! _}

```

Since our computations are stateless, the runtime engine can choose to run several instances of the computation `c1` concurrently, depending on run-time conditions.

We note that `c1` is not a reference to a particular instance of a computation. Rather, the computation `{ x: Int ⇒ ... }` is being defined _declaratively_, as a description of what needs to be done. The value `c1` plays the role of a label attached to the value 123, which specifies that the value 123 should be used as the input value `x` in the computation. To express this semantics more clearly, we can change our pseudo-code notation to

```scala
go { x: Int from c1 ⇒ ... }
c1 ! 123

```

The second requirement means that a chemical actor should be able to wait for, say, two messages at once, alowing us to write pseudo-code like this,

```scala
go { x: Int from c1, y: String from c2 ⇒ ... }
c1 ! 123
c2 ! "abc"

```

The two messages are of different types and are labeled by `c1` and `c2` respectively. The computation starts only after both messages have been sent.

It follows that messages cannot be sent to a linearly ordered queue or a mailbox; instead, messages must be kept in an unordered bag, as they will be consumed in an unknown order.

It also follows that we may now define several computations that _jointly contend_ on input messages:

```scala
go { x: Int from c1, y: String from c2 ⇒ ... }
go { x: Int from c1, z: Unit from d1 ⇒ ... }

```

We find that messages that carry data are now completely decoupled from computations that consume the data. All computations start concurrently whenever their input messages become available. The runtime engine needs to resolve message contention by making a non-deterministic choice of the messages that will be actually consumed.

This concludes the second and final step towards the chemical machine paradigm. It remains to use the Scala syntax instead of pseudo-code.
The syntax used by `Chymyst` looks like this:

```scala
val c1 = m[Int]
val c2 = m[String]
go { c1(x) + c2(y) ⇒ ... }
c1(123)
c2("abc")

```

Here, `m[Int]` creates a new message label with values of type `Int`.

As we have just seen, the chemical machine paradigm is a radical departure from the Actor model:

- Whenever there are sufficiently many input messages available for processing, the runtime engine may automatically instantiate several concurrent copies of the same computation that will consume the input messages concurrently. This is the main method for achieving parallelism in the chemical paradigm. The runtime engine is in the best position to balance the CPU load using low-level OS threads. The users do not need to concern themselves with the details of how many concurrent actors to instantiate at any given time.
- Since chemical actors are stateless and instantiated automatically on demand, users do not need to implement actor lifecycle management, actor supervision hierarchies, backup and recovery of the actor's internal state, or a special “dead letter” actor. This removes a significant amount of complexity from the architecture of concurrent applications.
- Input contention is used in the chemical machine paradigm as a mechanism for synchronization and mutual exclusion. Since the runtime engine will arbitrarily decide which actor to run, input contention will result in nondeterminism. This is quite similar to the nondeterminism in the usual models of concurrent programming. For example, mutual exclusion allows the programmer to implement safe exclusive access to a resource for any number of concurrent processes, but the order of access among the contending processes remains unspecified.

In the rest of this book, “chemical actor” computations are called **reactions**, their input messages are called **input molecules**,
and messages sent by a chemical computation are called **output molecules** of the reaction.
