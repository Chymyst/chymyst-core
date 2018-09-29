# Distributed programming in the chemical machine

All programs considered in the previous chapters ran within a single JVM process on a single (possibly multi-core) computer.
This chapter describes the extension of the chemical machine paradigm to distributed programming, where programs can run concurrently and in parallel on several OS processes and/or on several computers.
The resulting paradigm is the **distributed chemical machine** (DCM).

Similarly to how the single-process chemical machine (CM) provides a declarative approach to concurrent programming, the DCM provides a declarative approach to distributed concurrent programming.

To motivate the main concept of the DCM, we need to consider the features that make the CM declarative.

In CM, the programmer merely specifies that certain computations need to be run concurrently and in parallel.
Computations are started automatically as long as the required input data is available.
The input data needs to be specially marked as “destined for” concurrent computations (i.e. emitted on molecules).
However, the code does not explicitly start new tasks at certain times, nor does it need explicitly to wait for or to synchronize processes that run in parallel.

Similarly, the DCM requires the programmer merely to _declare_ certain molecules as having the property of being distributed.
The runtime of the DCM will automatically distribute the chosen data across all JVM processes that participate in a given cluster.
We will call each of these processes a **DCM peer** running on the cluster.
From the programmer's point of view, it makes no difference whether a DCM peer is a JVM process that runs on the same machine or on a different machine within the cluster.

Also, there is no distinction between DCM peers (such as “master”/“worker”) at the architectural level.
Every DCM peer participates in the computation in the same way -- by consuming and emitting distributed molecules.
If needed, an application code can program certain DCM peers to perform specific functions differently from other DCM peers,
but this is an application's responsibility to implement.

The number of active DCM peers in a cluster may change with time, because individual DCM peers may become temporarily or permanently disconnected from ZooKeeper, or because the number of active DCM peers is intentionally increased or decreased by an external agent.
By default, computations do not explicitly depend on the number of DCM peers; the user code does not need to be aware of the current status of the cluster.
Progress towards and correctness of computational results is guaranteed regardless of the presence or absence of hardware connected to the cluster. 

To summarize, the DCM extends the CM by introducing **distributed molecules** (DMs) in addition to **local molecules**,
and by extending the operational semantics to coordinate data exchange between any number of DCM peers. 
The DCM runtime will also run local reactions with local molecules in the same way CM does.
The programmer's job remains to declare certain molecules and reactions, and to emit certain initial molecules, such as to implement the required application logic.

## Cluster configuration

The coordination of distibuted computations for DCM is performed by [Apache ZooKeeper](https://zookeeper.apache.org/).
When a DCM program runs on a cluster, each participating DCM peer must be connected to a running ZooKeeper instance.

The information about connecting to the cluster, such as the ZooKeeper URL and other parameters, are encapsulated in a value of type `ClusterConfig`.
The user code must create this value before a DCM program can be run.

```scala
implicit val clusterConfig = ClusterConfig(
  url = "localhost:2345",
  username = "zkUser",
  password = "zkPassword"
)

```

Each DCM peer is automatically assigned a unique ID in the cluster.
The ID can be read as `clusterConfig.clientId`.

## Serialization

All data carried by DMs is serialized using the [Chill](https://github.com/twitter/chill) library.

## Distributed molecules

An emitter for a distributed molecule is defined using the class constructor `DM` or the macro `dm`:

```scala
val a = dm[Int]
val c = new DM[Int]("c") // same as val c = dm[Int]

```

Distributed molecules differ from local molecules in two ways:

- they use a different emitter type: `DM` instead of `M`
- defining a `DM` needs an implicit `ClusterConfig` value to be available in scope

Distributed molecules are always non-blocking.

Defining reactions and emitting a distributed molecule is done in the same way as with local molecules:

```scala
val a = dm[Int] // Distributed molecule.
val c = m[Int] // Local molecule.
site( go { case a(x) + c(y) ⇒ ... })

(1 to 100).foreach(a)

```

Reactions may consume and emit distributed and/or local molecules in any combination.
The data carried by distributed molecules will be stored on the ZooKeeper and, in this way, made available to all the DCM peers within the cluster.
Thus, distributed molecules are emitted _into the cluster site_, not into a local reaction site.
At any time, any DCM peer may start a reaction that consumes any of the available distributed molecules.

## Example: distributed map/reduce

In [Chapter 2](chymyst02.md) we have implemented a concurrent map/reduce computation using the following code:

```scala
// Declare the "map" and the "reduce" functions.
def f(x: Int): Int = x * x
def reduceB(acc: Int, x: Int): Int = acc + x

val arr = 1 to 100

// Declare molecule emitters.
val carrier = m[Int]
val interm = m[(Int, Int)]

// Declare the reaction for the "map" step.
site(
  go { case carrier(x) ⇒ val res = f(x); interm((1, res)) }
)

// Declare the reaction for the "reduce" step,
// keeping track of the number of partially reduced items.
site(
  go { case interm((n1, x1)) + interm((n2, x2)) ⇒
    val x = reduceB(x1, x2)
    val n = n1 + n2
    if (n == arr.length) println(x) else interm((n, x))
  }
)

// Emit initial molecules.
arr.foreach(i ⇒ carrier(i))

```

To make this program distributed, we begin by defining the `carrier()` and `interm()` molecules as DMs.
The only changes in the code are the different emitter types and the addition of an implicit `ClusterConfig`:

```scala
implicit val clusterConfig = ??? // Perhaps, read some configuration files here.

// Declare the "map" and the "reduce" functions.
def f(x: Int): Int = x * x
def reduceB(acc: Int, x: Int): Int = acc + x

val arr = 1 to 100

// Declare distributed molecule emitters.
val carrier = dm[Int]
val interm = dm[(Int, Int)]

// Declare the reaction for the "map" step.
site(
  go { case carrier(x) ⇒ val res = f(x); interm((1, res)) }
)

// Declare the reaction for the "reduce" step,
// keeping track of the number of partially reduced items.
site(
  go { case interm((n1, x1)) + interm((n2, x2)) ⇒
    val x = reduceB(x1, x2)
    val n = n1 + n2
    if (n == arr.length) println(x) else interm((n, x))
  }
)

// Emit initial molecules.
arr.foreach(carrier)

```

With virtually no code changes, we have created a first distributed application!

To clarify how the DCM will run this program, consider several DCM peers running exactly the same program code.
Each of the DCM peers will emit 100 copies of the molecules `carrier()` carrying different values.
Since the molecule `carrier()` is distributed, all these copies will be consumed by all DCM peers in parallel and in arbitrary order to start the "map" reaction.
Each DCM peer may run several concurrent copies of the "map" reaction; the code leaves this decision to the implementation.
In any case, the result will be that a number of molecules `interm()` are emitted back into the cluster site.

After that, the "reduce" reactions will be started by the DCM peers, all running in parallel.
All the `interm()` molecules will be consumed until the final result is obtained.

This DCM program computes a final `Int` value and prints it.
However, two problems remain with this code:

- All the participating DCM peers run the same code, emitting the initial `carrier()` molecules via `arr.foreach(carrier)`. We need to run `arr.foreach(carrier)` only once, rather than many times.
- The DCM peer that performs the final "reduce" step is chosen non-deterministically. We need more control over the code that obtains the final value; for example, we need to send this value to a specific, designated DCM peer.

We can implement these requirements if we add a reaction that reports the final value.
While this reaction is defined in all DCM peers, a single designated peer (call it the "driver") will actually run that reaction.
The same DCM peer will emit the initial molecules.
We omit the code that defines `isDriver` to specify which DCM peer is the driver; our intention is to have `isDriver == true` only in a single, designated DCM peer. 

```scala
implicit val clusterConfig = ??? // Perhaps, read some configuration files here.

val isDriver: Boolean = ??? // Read from configuration.

// Declare the "map" and the "reduce" functions.
def f(x: Int): Int = x * x
def reduceB(acc: Int, x: Int): Int = acc + x

val arr = 1 to 100

// Declare distributed molecule emitters.
val carrier = dm[Int]
val interm = dm[(Int, Int)]

// Declare the reaction for the "map" step.
site(
  go { case carrier(x) ⇒ val res = f(x); interm((1, res)) }
)

val result = dm[Int] // Distributed molecule.

// Declare the reaction for the "reduce" step,
// keeping track of the number of partially reduced items.
site(
  go { case interm((n1, x1)) + interm((n2, x2)) ⇒
    val x = reduceB(x1, x2)
    val n = n1 + n2
    if (n == arr.length) result(x) else interm((n, x))
  }
)

site(
  go { case result(x) if isDriver ⇒ println(x) }
)

// Emit initial molecules.
if (isDriver) arr.foreach(carrier)

```

The final result will now be printed on the designated DCM peer, regardless of where the final "reduce" step was performed.
As before, all DCM peers run identical code; only the configuration files or the command-line options may be set differently at deployment time.

## Identifying the distributed data across DCM peers

### The requirement of identical reaction code

Just as with local molecules, it is necessary to bind distributed molecules to reaction sites,
which is done by defining reactions that consume them.

Reactions that consume DMs must have _the same Scala code_ in every DM peer.
In this way, the DCM can identify the distributed data and share it automatically across instances.
The distributed data is intended to be consumed by an arbitrary participating DM peer,
which is safe only if every DM peer runs _the same reaction code_ consuming the data. 

To see this, consider two DCM peers.
Suppose that the first DCM peer runs a program such as

```scala
val a = dm[Int]
val c = dm[Int]
val f: Int ⇒ Int = ???
site( go { case a(x) ⇒ c(f(x)) } ) // Some computation.

```

while the second DCM peer runs

```scala
val a = dm[Int]
val c = dm[Int]
val d = m[Int]
val g: (Int, Int) ⇒ Int = ???
site( go { case a(x) + d(y) ⇒ c(g(x, y)) } ) // Another computation.

```

Both DCM peers are be able to consume a copy of the distributed molecule `a()`.
However, the resulting computations will be quite different depending on which DCM peer succeeds in consuming `a()` first.
If many copies of `a()` are emitted, the programmer has no control over the choice of the DSM peers that consume various copies of `a()`.

It appears to be undesirable to allow this sort of non-determinism because it yields completely unpredictable results.
When we mark a molecule as distributed, the intent is to distribute a given, fixed computation across different executors to obtain the result faster or to provide resilience to errors.

Therefore, `Chymyst` requires that all DCM peers should define _identical Scala code_ for reactions that consume a given distributed molecule such as `a()`.

If two DCM peers run two different reactions as shown above, the DCM will consider their definitions of `a()` as "incompatible" with each other.
The result will be that the molecule `a()` will _not_ be shared between these two DCM peers,
even though both programs define a molecule called `"a"`.

The DCM will make a reaction site into a distributed reaction site if two conditions are met:

- a reaction site contains some input molecules of type `DM`
- the Scala code of all reactions in the reaction site is _syntactically identical_ in several DCM peers (the order of reactions is unimportant)

However, there may be runtime parameters that have different values for different DCM peers.
We have seen an example of this situation when we implemented the distributed map/reduce.
The reaction

```scala
go { case result(x) if isDriver ⇒ println(x) }

```

sets a runtime parameter `isDriver` by reading a configuration file.
Another example of a runtime parameter is the function `f` in the reaction `go { case a(x) ⇒ c(f(x)) }`.
However, it is important that the Scala code of the entire reaction site is syntactically the same in every DCM peer.
This syntactic equality is sufficient for the DCM to recognize that these reaction sites correspond to each other and need to share input molecules.

The programmer is still free to arrange the values of any runtime parameters according to application-specific needs.
In this way, some DCM peers could play a different role from other DCM peers even though they run identical Scala code for their reaction sites. 
However, the "chemical logic" (the input and the output molecules in each reaction) is required to be identical in all DCM peers.

## Exercise: distributed counter

Modify the code in [Chapter 1](chymyst01.md) for the concurrent counter to make it distributed,
so that the counter can be incremented or decremented on any of the DCM peers.

Make sure that there is only one copy of the `counter` molecule in the cluster site.

# The DCM protocol internals

The details of the DCM peer communication are largely irrelevant to high-level DCM programming, and are provided for reference.

## Cluster sessions

Each DCM peer establishes one session with the ZooKeeper cluster.
This *cluster session* is first opened when a `ClusterConfig` value is created. 
The single cluster session is used by all reaction sites that consume or emit distributed molecules.

A DCM peer may open several cluster sessions if different `ClusterConfig` values specify different cluster URLs.
However, a single reaction site may not consume molecules from different clusters (this would have created unsolvable deadlock problems).

Since `ClusterConfig` values are immutable, they are compared as values (not as JVM object identities).
Therefore, different parts of the program may create their own copies of `ClusterConfig` values, still resulting in a single cluster session as long as all `ClusterConfig` values contain the same configuration data.

If the cluster session is disconnected due to time-out or network failure, a new session will be created automatically to reconnect to the cluster if possible.
Failure to connect to the cluster will be logged, and will result in stopping the emission or consumption of any DMs, while local (non-distributed) reactions will still proceed normally.

## New data available

New DMs can become available on the cluster site in two situations:

- after explicitly emitting a DM (either within a reaction or outside reactions), the DM becomes available for participating DCM peers to consume
- after a network failure or other failure in a DCM peer that previously consumed some DM from the cluster site and started a reaction, the DCM determines that the DCM peer failed to complete the reaction; the DCM then returns the affected DMs automatically to the cluster site, making them again available

The DCM peer receives a "new data" notification each time a new distributed molecule becomes available on the cluster.
A "new data" notification is also generated at the beginning of a cluster session.

The content of the "new data" notification is the list of DM emitters that have new data available on the cluster site.
The DCM peer automatically identifies the local DM emitters corresponding to these DMs.

The DCM peer then determines the list of reaction sites that consume any of the newly available DMs.
The "new data" notification is forwarded to each of these reaction sites.

## Reaction scheduling

## Reaction start

## Reaction completion

## Failure to complete a reaction

## Emitting a new distributed molecule

When any code in a DCM peer (within a reaction or outside reactions) emits a new DM, the DM must be already bound to some reaction site locally defined in the DCM peer. 
The newly emitted DM is first sent to that reaction site using the private method `emitDistributed()`.
The new DM is temporarily stored in a multiset for "outgoing" DMs.

There are two different situations where a DM can be emitted by a DCM peer:

1. The DM is emitted as a result of a reaction that consumes other DMs as input.
2. The DM is emitted outside reactions, or in a reaction that does not consume any DMs as input.

In the first case, the reaction's inputs have been consumed within a certain cluster session whose ID is preserved within the `ChymystThread` structure.
If this session expires, the DCM will decide that the current DCM peer has failed to complete the reaction.
In that case, the current DCM peer should not emit DMs as products of the reaction.

Therefore, the DCM peer will check the current session ID before emitting any DMs from the "outgoing" multiset.
If the current session ID differs from that of the reaction just finished, all DMs from the "outgoing" multiset will be cleared. 
Otherwise, the DMs are emitted into the cluster.

In the second case, the current DCM peer is free to emit DMs.

Therefore, there are two ways of emitting a DM: with restriction to a specified cluster session ID, and without a specified session ID.

If the connection to the cluster is up, the ZooKeeper client will send the DM to the cluster, and if successful, clear the DM from the "outgoing" multiset. 
If the connection fails at that time, the same logic applies: the DMs are emitted only if the current cluster session ID is the same as the session ID specified by the emission request.
