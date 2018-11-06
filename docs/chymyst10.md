# Distributed programming in the chemical machine

All programs considered in the previous chapters ran within a single JVM process on a single (possibly multi-core) computer.
This chapter describes the extension of the chemical machine paradigm to distributed programming, where programs can run concurrently and in parallel on several OS processes and/or on several computers.
The resulting paradigm is the **distributed chemical machine** (DCM).

Similarly to how the single-process chemical machine (CM) provides a declarative approach to concurrent programming, the DCM provides a declarative approach to distributed concurrent programming.

To motivate the main concept of the DCM, we need to consider the features that make the CM declarative.

In CM, the programmer merely specifies that certain computations need to be run concurrently and in parallel.
Computations are started automatically as long as the required input data is available.
The input data needs to be specially marked as “destined for” concurrent computations; this marking is done through emitting molecules with data.
However, the code does not explicitly start new tasks at certain times, nor does it explicitly wait for or synchronize processes that run in parallel.

Similarly, the DCM requires the programmer merely to _declare_ certain molecules as having the property of being distributed on a given cluster.
We call these molecules **distributed molecules** (DMs), in distinction to **local molecules** (LMs, i.e. non-distributed molecules).

Reactions may consume DMs and/or LMs as input molecules.
If a reaction consumes some DMs, it is called a **distributed reaction** (DR).
A **distributed reaction site** (DRS) is a reaction site that declares one or more distributed reactions.
In other words, a DRS is a reaction site to which at least one DM is bound.

Data carried by DMs is thus declared to be available to all JVM processes that participate in the cluster. 
We will call each of these processes a **DCM peer** running on the cluster.
From the programmer's point of view, it makes no difference whether a DCM peer is a JVM process that runs on the same machine or on a different machine within the cluster.
The DCM runtime will automatically distribute the DMs among all participants.

Also, there is no distinction between DCM peers at the protocol level.
For instance, unlike [Apache Spark](https://spark.apache.org/), there is no concept of “driver” vs. “worker” peers.
Every DCM peer participates in the computation in the same way -- by consuming and emitting distributed molecules (DMs).

As we will see later in this chapter, it is natural in the DCM paradigm to run identical JVM code on all DCM peers.
This does not mean, however, that the DCM peers must always perform exactly the same computations.
If needed, certain DCM peers may be configured to perform specific functions differently from other DCM peers.
The programmer can easily use the DCM paradigm to implement a “driver”/“worker” architecture or another hierarchy of DCM peers. 

The number of active DCM peers in a cluster may change with time, because individual DCM peers may become temporarily or permanently disconnected from the network, or because the number of active DCM peers is intentionally changed by an external agent.
By default, computations do not explicitly depend on the number of DCM peers; the user code does not need to be aware of the current status of the cluster.
Progress towards completion and correctness of computational results is guaranteed regardless of the presence or absence of hardware connected to the cluster.
If more DCM peers become available, computations may proceed faster.

To summarize, the DCM extends the CM by introducing **distributed molecules** (DMs) in addition to ,
and by extending the operational semantics to coordinate the exchange of data between any number of DCM peers that form a cluster.
The DCM runtime will continue to run reactions with local molecules in the same way CM does.

The programmer's job remains to implement the required application logic by declaring a number of molecules and reactions, and by arranging to emit certain initial molecules.
In addition, the programmer will need to designate some molecules as DMs and others as local molecules.

## Cluster configuration

The coordination of distibuted computations for DCM is performed by [Apache ZooKeeper](https://zookeeper.apache.org/).
When a DCM program runs on a cluster, each participating DCM peer must be connected to a running ZooKeeper instance.

The information about connecting to the cluster, such as the ZooKeeper URL and other parameters, is encapsulated in a value of type `ClusterConfig`.
The user code must create this value before a DCM program can be run, for example:

```scala
implicit val clusterConfig = ClusterConfig(
  url = "localhost:2345",
  username = "zkUser",
  password = "zkPassword"
)

```

Each DCM peer is automatically assigned a unique ID in the cluster.
The ID can be read as `clusterConfig.peerId` and persists throughout the lifetime of each JVM process.

## Distributed molecules

An emitter for a distributed molecule is defined using the class constructor `DM` or the macro `dm`:

```scala
val a = dm[Int]
val c = new DM[Int]("c") // same as val c = dm[Int]

```

Distributed molecules differ from local molecules in several ways:

- They use a different emitter type: `DM` instead of `M`.
- Defining a `DM` needs an implicit `ClusterConfig` value to be available in scope.
- Distributed molecules are always non-blocking.
- Distributed molecules may be consumed by any DCM peer connected to the cluster.
- If a cluster connection becomes unavailable (e.g. due to a network timeout), emitting a DM will fail and instead throw an exception.
- If a cluster connection is restored after being temporarily unavailable, emitting a DM may fail if the DM is bound to a specific cluster session (see below).

Defining reactions and emitting a distributed molecule is done in the same way as with local molecules:

```scala
val a = dm[Int] // Distributed molecule.
val c = m[Int] // Local molecule.
site( go { case a(x) + c(y) ⇒ ??? }) // Reaction site.

(1 to 100).foreach(a) // Initial molecules.
c(0)

```

Reactions may consume and emit distributed and/or local molecules in any combination.
The data carried by distributed molecules will be stored on the ZooKeeper instance and, in this way, will be made available to all the DCM peers within the cluster.
Thus, we say that distributed molecules are emitted _into the cluster_ and not into a local reaction site.

At any time, any DCM peer may start a reaction that consumes any of the available molecules, as long as all the required local molecules as well as distributed molecules are present.
The LMs will be consumed from the local reaction site, while the DMs will be taken from the cluster.
In this way, each reaction site that consumes DMs is automatically shared with other DCM peers.

## Example: distributed map/reduce

In [Chapter 2](chymyst02.md) we have implemented a concurrent map/reduce computation with the following code:

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
implicit val clusterConfig = ??? // Perhaps read some configuration files here.

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
implicit val clusterConfig = ??? // Perhaps read some configuration files here.

val isDriver: Boolean = ??? // Read this from configuration.

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

## Exercise: distributed counter

Modify the code in [Chapter 1](chymyst01.md) for the concurrent counter to make it distributed,
so that the counter can be incremented or decremented on any of the DCM peers.

Make sure that there is only one copy of the `counter` molecule in the cluster site.

## Identifying the distributed data across DCM peers

When one DCM peer emits a distributed molecule (DM) into the cluster, other DCM peers must be able to consume that molecule and to start some reactions.
In order to do that, the DCM peer must identify the local reaction value that can consume the DM, and the corresponding reaction site.

### The requirement of identical reaction code

Just as with local molecules, it is necessary to make DMs bound to reaction sites,
which is done by defining reactions that consume these DMs.

Reactions that consume DMs must have _the same Scala code_ in every DCM peer.
In this way, the distributed data can be identified and shared automatically across DCM peers.

The reason for the same-code requirement is that a given item of distributed data may be consumed by an arbitrary participating DCM peer,
which will produce predictable results only if every DCM peer runs _the same reaction code_ after consuming the data. 

To see this on an example, consider two DCM peers.
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
site( go { case a(x) + d(y) ⇒ c(g(x, y)) } ) // A different computation.

```

Both DCM peers are be able to consume a copy of the distributed molecule `a()`.
However, the resulting computations will be different depending on which DCM peer succeeds in consuming `a()` first.
If many copies of `a()` are emitted, the programmer has no control over the choice of the DSM peers that consume various copies of `a()`.

It appears to be undesirable to allow this sort of non-determinism because it yields completely unpredictable results
that the programmer is powerless to control.
When we define a molecule as a DM, the intent is to distribute a given, fixed computation across different computers to obtain the result faster or to provide resilience to errors,
rather than to produce random results that depend on network latencies and CPU loads.

Therefore, `Chymyst` requires that all DCM peers should define _identical Scala code_ for reactions that consume a given distributed molecule such as `a()`.

If two DCM peers run two different reactions as shown above, the DCM will consider their definitions of `a()` as "incompatible" with each other.
The result will be that the molecule `a()` will _not_ be shared between these two DCM peers,
even though both programs define a molecule called `"a"`.

For DMs that are designed to be shared between DCM peers, it is natural to call the DMs by the same names in all participating DCM peers. 
So the DCM will make a reaction site into a distributed reaction site and identify the DRS instances across DCM peers if these conditions are met:

- A reaction site is a DRS, i.e. some input molecules of type `DM` are bound to it.
- The Scala code of all reactions in this DRS is _syntactically identical_ across several DCM peers (the order of reactions and the order of input molecules within a reaction is unimportant).
- All input molecules in the DRS have the same names (as given by the `_.name` attribute of the molecule emitter) and the same types of the payload values across several DCM peers.

The requirement of identical code might appear very restrictive; however, it does not actually limit the expressive power of distributed computations in DCM.

For instance, there may be runtime parameters that have different values for different DCM peers.
We have seen an example of this situation when we implemented the distributed map/reduce.
The reaction

```scala
go { case result(x) if isDriver ⇒ println(x) }

```

sets a runtime parameter `isDriver` by reading a configuration file.
As a result, this reaction can be enabled in some DCM peers but not in others.

Another example of a runtime parameter is the function `f` in the reaction `go { case a(x) ⇒ c(f(x)) }`.

However, it is important that the Scala code of the entire reaction site is syntactically the same in every DCM peer.
This syntactic equality is necessary for the DCM to recognize that these reaction sites correspond to each other and need to share input molecules.

The programmer is still free to arrange the values of any runtime parameters according to application-specific needs.
In this way, some DCM peers could play a different role from other DCM peers even though they run identical Scala code for their reaction sites. 
However, the "chemical logic" (the input and the output molecules in each reaction) is required to be identical in all DCM peers.

If the application requires several different instances of a DRS that happen to have the same code (e.g. the DRS is defined by calling a helper function with parameters),
it is important to distinguish these instances in the DCM.
The way to do that is to assign different molecule names for at least some of the input molecules of the DRS. 
In this way, each DRS will become "chemically different", and the DCM will be able to identify them across the DCM peers in the cluster.

This consideration gives rise to the single-instance requirement, which we explain next.

### The requirement of single-instance reaction sites

Consider the situation when a DM carries data that itself contains a molecule emitter (e.g. a DM of type `DM[M[Int]]`, `DM[DM[Int]]` and so on).
When this DM is consumed by a DCM peer, a typical program will want to emit a molecule using the emitter carried by the DM.
How can the runtime engine identify the reaction site to which this molecule is bound?

It is clear that all molecule emitters carried by DMs must be bound to reaction sites that can be unambiguously identified by their hash sum.
Otherwise, a DCM peer will be unable to identify the reaction that needs to be run when a molecule is emitted using an emitter carried by a DM.

The same consideration applies to molecule emitters for DMs themselves.

Therefore, a DCM peer should contain only a _single instance_ of a reaction site that consumes a distributed molecule, or that consumes a molecule whose emitter is carried by a distributed molecule.
Such reaction sites are called **single-instance**.

To define a single-instance reaction site, it is sufficient to make sure that the Scala code of some reactions is different from the code of all other reaction sites,
or when the Scala code is the same, that the names or types of some input molecules are different.

In `Chymyst`, each reaction site is declared in a certain local scope.
A reaction site will not be single-instance (i.e. will be **multiple-instance**) if there are two or more distinct local scopes that define the same input molecule names and the same set of reactions for these molecules.  
This will be the case, for instance, if a reaction site is declared by a helper function:

```scala
// Incorrect code.
// This function defines a reaction site and returns some emitters.
def makeQuery(delta: Int)(implicit clusterConfig: ClusterConfig): (DM[Int], B[Unit, Int]) = {
  val data = dm[Int]
  val get = b[Unit, Int]
  site( go { case data(a) + get(_, r) ⇒ r(a); data(a + delta) })
  (data, get)
}
// Create two instances of the reaction site.
val (data1, get1) = makeQuery(10)
val (data2, get2) = makeQuery(20)

```

This code is incorrect because the distributed molecule `data1()` cannot be distinguished from `data2()` across DCM peers.
Both molecules have identical types (`DM[Int]`), identical names (`"data"`), and are bound to reaction sites that have identical Scala source code.
If a DCM peer emits `data1(123)` to the cluster, other DCM peers will be able to see that a molecule with name `data` is emitted to the reaction site with a specific Scala code hash.
But there exist two reaction sites with the same code hash, and two molecule emitters with name `data`.

The runtime engine cannot use the fact that the two reaction sites were created using different values of the parameter `delta`, because this is a runtime parameter that is invisible in the reaction code at compile time, and unknown to other DCM peers.
So the DCM peer does not have enough information to choose between `data1` and `data2` or between the two reaction sites.

We say that the repeated calls to `makeQuery()` have defined a multi-instance reaction site in this code example.

The same problem will occur with multi-instance reaction sites that consume local (non-distributed) molecules whose emitters are carried as data by some DMs.
The DCM peer will not have enough information to distinguish the RSs by their Scala code hash.

Generally, reaction sites created by different function invocations will all have identical Scala code.
However, these reactions (and the molecule emitters bound to them) will be _chemically_ different within the local scope.
In `Chymyst`, this is implemented using local JVM object identities:
Calling a helper function several times will create new JVM objects, and molecule emitters are different when they are different JVM objects.

When a DM carries a molecule emitter and the reaction code emits a new molecule using that emitter, the runtime engine needs to identify the specific instance of the reaction site to which this molecule emitter is bound.
This identification becomes impossible if several reaction sites are created dynamically from identical Scala code but (possibly) different parameters and different object identities for molecule emitters.
Other DCM peers have no access to Java object identities in a different JVM, since JVM object references cannot be directly transferred over the network.

### Example: inability to distinguish multi-instance DRSs

To illustrate this problem, consider a situation with a multi-instance DRS,
where two DCM peers run the following (identical) code that defines two reaction sites using a helper function and then send a molecule emitter on a DM:

```scala
// Incorrect code.
// This function defines a reaction site and returns an emitter.
// We assume that this function always creates reaction sites with the same code hash. 
def makeReaction(param: Int): M[Int] = ???

val isDriver: Boolean = ??? // Read configuration.

// Create two instances of the reaction site.
val data1 = makeReaction(10)
val data2 = makeReaction(20)

// This DM will carry a molecule emitter.
val carrier = dm[M[Int]]

// This DRS will consume `carrier()` and emit `data()`.
site( go { case carrier(data) ⇒ data(123) } )

// The driver node sends `data1` to the cluster.
if (isDriver) carrier(data1)

```

Assume that one DCM peer was configured to be the "driver" and the other as the "worker" (`isDriver == false`).
From the point of view of the driver JVM, `data1` and `data2` are chemically different because they have different JVM object identities.

Now suppose that the DM `carrier(data1)` was emitted by the driver JVM and consumed by the worker JVM.
Once the worker consumes `carrier()`, it will run its reaction and so will need to emit a molecule `data(123)`, where `data` is the emitter carried by the DM `carrier()`.
It seems to be the programmer's intention that the emitted molecule should be `data1(123)`.
But how can the worker JVM deduce this?

Indeed, a molecule emitter `data1` also exists on the worker JVM.
The worker needs to make the correspondence between the locally defined emitter `data1` and the data of type `M[Int]` carried by the DM `carrier()`. 
The data on `carrier()` was received by the worker JVM from the network and can be deserialized into a value of type `M[Int]`.
However, this should not be a _new_ molecule emitter; the intended functionality is to make this molecule emitter identical to `data1` in the worker JVM.
How can the worker JVM decide that the serialized data carried by `carrier()` corresponds to the molecule emitter `data1`, and not to any other molecule emitter of type `M[Int]`?

Molecule emitters can be identified in three different ways:

1. By the molecule's printable name (which is normally used for debugging purposes only).
2. By the name of the local Scala identifier for the molecule emitter, such as `data1`.
3. By the Scala code hash of the reaction site to which the molecule is bound, together with the site-wide molecule index (unique among all molecules bound to the reaction site).

The first two options are unreliable, since the user code can define local values equal to molecule emitters.
Local variable names, as well as the molecules' printable names, should be easily changeable according to the programmer's convenience,
and thus should not be used to denote the business logic of the application.

It remains to identify molecule emitters by the hash of the reaction site and the site-wide molecule index.
However, this identification is impossible here, since the reaction sites for both emitters have identical code.

Reliable cross-cluster identification of molecule emitters is possible only for molecules bound to single-instance reaction sites.
For this reason, DCM restricts DMs, as well as any molecules whose emitters are carried as data by DMs, to be bound to single-instance reaction sites.
If it is necessary for application code to create several reaction sites with the same Scala code, the reaction sites must differ from each other at least in the names of some input molecules.
The Scala code hash for a reaction site will be computed at run time, after all input molecules are bound to that RS, and will depend on the names of all input molecules as well as on the Scala code of all reactions in the RS.

### Examples of avoiding multi-instance DRSs

As a simple fix for the first example shown above, we can make a molecule name depend on the parameter `delta`:  

```scala
// Corrected code.
def makeQuery(delta: Int)(implicit clusterConfig: ClusterConfig): (DM[Int], B[Unit, Int]) = {
  val data = new DM[Int](s"data_$delta")
  val get = b[Unit, Int]
  site( go { case data(a) + get(_, r) ⇒ r(a); data(a + delta) })
  (data, get)
}
// Make two instances of the reaction site.
val (data1, get1) = makeQuery(10)
val (data2, get2) = makeQuery(20)

```

The resulting reaction sites will be single-instance as long as the programmer does not call `makeQuery()` with the same value of `delta`.

Similarly, the second example can be fixed by making the molecule names depend on the parameter of the function `makeReaction()`.

## Example: broadcasting

Suppose we need to determine which DCM peers are currently active in the cluster.
To do that, we would like to "broadcast a message" from a designated DCM peer to every other DCM peer.

### Sending a broadcast message

According to the chemical machine semantics, a message is represented by a molecule carrying data, and we cannot know exactly when a given copy of the molecule is actually consumed by a reaction.
Thus we need a molecule that carries the broadcast message.
Also, when more DCM peers join the cluster at a later time, these peers must still be able to consume a copy of the broadcast message emitted earlier.  

Since the broadcast message must go from one DC peer to another, we must model it by a distributed molecule, say `broadcast()`.
Other requirements mean that:

- the `broadcast()` molecule must be consumed by each DCM peer in some reaction, to be defined;
- any DCM peer, including DCM peers that connect to the cluster after the broadcast, must be able to consume this molecule;
- any given DCM peer consumes this molecule only once.

It also follows that we cannot have an "instantaneous" broadcast since it is not known when any given DCM peer can connect to the cluster and when it will become able to start reactions.
The broadcast message will have to reach each of the DCM peers sequentially, one by one.
This will be implemented by letting the DCM peers consume a copy of the `broadcast()` molecule. 
 
To simplify this example, assume that the `broadcast()` molecule carries `Unit` values:

```scala
val broadcast = dm[Unit]

```

The `broadcast()` molecule must remain available to other peers after being consumed by a DCM peer.
However, a given DCM peer should not consume this molecule a second time.
The standard way of preventing a reaction is to withhold some input molecule.
Therefore, the reaction that consumes the `broadcast()` molecule must have another input molecule, say `see()`:

```scala
val see = m[Unit]
val seeBroadcast = go { case broadcast(_) + see(_) ⇒ println("Got broadcast") ; broadcast() }

```

The reaction `seeBroadcast` will run exactly once if the molecule `see()` is local (non-DM), and if we make sure that there is only one copy of `see()` ever emitted.
However, we should not make `see()` a static molecule, since we do not want to emit `see()` again after consuming `broadcast()`.

```scala
site(seeBroadcast)
see()

```

Now we need to arrange for `broadcast()` to be emitted by one of the DCM peers.
We can do this by conditionally emitting `broadcast()` according to a configuration value `isDriver`, similarly to our implementation of distributed map/reduce:

```scala
if (isDriver) broadcast()

```

At this point, we have created a DCM program that will run the `seeBroadcast` reaction in every DCM peer exactly once.
Each DCM peer will consume the single copy of `broadcast()`, print a message, and emit `broadcast()` back into the cluster for other DCM peers to consume.
If necessary, the `broadcast()` molecule could carry some data, and the `seeBroadcast` reaction could process this data, implementing any required functionality.

What if we emit several copies of `broadcast()` with identical data?
Each DCM peer will still consume only one copy of `broadcast()` and emit it back.
The resulting computations will be exactly the same, except that maybe more DCM peers will be able to consume the broadcast message at the same time.
So, we may emit one or more copies of `broadcast()` at our discretion, in order to improve the throughput of the broadcast operation.

### Responding to a broadcast

It remains to implement a response that will allow the "driver" DCM peer to know how many other DCM peers exist in the cluster.

It is clear that the response from each DCM peer may arrive at an unpredictable time and needs to be handled sequentially by the "driver" DCM peer.
The response data must be transported from one DCM peer to another, therefore it must be a DM.
Let us call it `response()` and make it carry `String`-valued data (say, representing the DCM peer's unique ID).
We modify the previous code as follows:

```scala
val response = dm[String]
val peerName = implicitly[ClusterConfig].peerId
val seeBroadcast = go { case broadcast(_) + see(_) ⇒ broadcast(); response(peerName) }
site(seeBroadcast)
see()

```

The `response()` molecule must be consumed by a reaction that accumulates all peer names into a list.
It is natural to model this by a local molecule, e.g. `peers()`:

```scala
val peers = m[List[String]]
site(go { case peers(ps) + response(s) ⇒ peers(s :: ps) })
if (isDriver) peers(Nil)

```

Since only the "driver" DCM peer emits the `peers()` molecule, the `response()` molecules will not be consumed by any other DCM peers.

The `response()` molecules will be emitted to the cluster and then consumed by this reaction at unpredictable times.
At any time, the `peers()` molecule will contain the latest available list of peers.

### Exercise: peer removal

The example shown above does not implement disconnection of peers; the semantics is that any peer may be temporarily disconnected from the cluster but may eventually reconnect.

Implement an explicit message that tells the "driver" that a certain DCM peer is being permanently removed from the cluster.
The result must be that the `peers()` molecule (which is only present on the "driver") carries an updated list of DCM peer IDs that does not contain the removed ID.

## Example: distributed chat

To implement a simple distributed chat application, we need just two pieces of functionality:

- find the list of available chat participants
- send a chat message from one specific participant to another 

In the DCM paradigm, there are no "servers" or "clients"; every DCM peer runs identical code and participates in the distributed computation in the same way.
We will assume for simplicity that there will be one chat user per DCM peer, and that DCM peers do not permanently leave the chat.

To derive the DCM program for the chat application, we begin by considering the necessary data that must be distributed:

- the list of available chat participants' names as `List[String]`
- message data and the name of the target participant for that message

Since the participant list needs to be shared among all DCM peers, it is sufficient to have a single copy of a DM carrying this list.
Each participant needs to add their name to the list:

```scala
val users = dm[List[String]]
val myName = dm[String]
site(go { case users(us) + myName(name) ⇒ users(name :: us) })

val peerName = implicitly[ClusterConfig].peerId
myName(peerName)
if (isDriver) users(Nil)

```

We omit an application-specific logic that selects a user for chatting and only focus on implementing the chat messages.
It is clear that a message from DCM peer `A` to DCM peer `B` must be represented by a distributed molecule emitted by `A` into the cluster
and consumed by a "message reception" reaction running on the DCM peer `B`, such as `go { case message(x) ⇒ ... }`.

To implement this functionality, we need to make sure that the message reception reaction does not start on any other DCM peers. 
A difficulty is that the DCM paradigm assumes that any available distributed molecule can be consumed by _any_ DCM peer that defines a reaction site to which that molecule is bound.
We cannot specify in an _ad hoc_ manner that a specific copy of a DM should be consumed only by a specific DCM peer.

One possible solution is to define a _chemically unique_ message reception reaction at each DCM peer.
The reaction will be chemically unique if it consumes a DM with a uniquely chosen name.
For simplicity, we will use the DCM peer's ID as the name of that molecule:

```scala
val message = new DM[String](peerName) // Molecule name assigned manually.
site(go { case message(x) ⇒ println(s"Peer $peerName gets message $x")})

```

Each DCM peer will now have its own unique reaction site for receiving chat messages.
The input molecule for that reaction is a unique molecule `message()` that must be used for sending messages to that DCM peer.

It remains to make these molecules' emitters available to other peers.
To achieve that, we will make the `myName` molecule carry the molecule emitter for `message`, rather than the name string.

With these changes, the code of the chat application becomes:

```scala
val peerName = implicitly[ClusterConfig].peerId

val users = dm[List[DM[String]]]
val myMessage = dm[DM[String]]
site(go { case users(ms) + myMessage(mess) ⇒ users(mess :: ms) })

val message = new DM[String](peerName) // Molecule name assigned manually.
site(go { case message(x) ⇒ println(s"Peer $peerName gets message $x")})

myMessage(message)
if (isDriver) users(Nil)

``` 

To send a message to a DCM peer, we need to fetch the message molecule list carried by `users()`, choose a molecule from that list, and emit that molecule with our message.

# The DCM protocol internals

The details of the DCM peer communication are largely irrelevant to high-level DCM programming, and are provided for reference.

## Cluster sessions

Each DCM peer establishes one session with the ZooKeeper cluster.
This *cluster session* is first opened when some distributed reaction site is activated.
The single cluster session is used by all reaction sites that consume or emit distributed molecules on the same cluster.
Clusters are identified by ClusterConfig` values.

A DCM peer may open several cluster sessions if different `ClusterConfig` values specify different cluster URLs.
However, a single reaction site may not consume distributed molecules from different clusters (this would create unsolvable deadlock problems).

Since `ClusterConfig` values are immutable, they are compared as values (not as JVM object pointers).
Therefore, different parts of the program may create their own copies of `ClusterConfig` values, still resulting in a single cluster session as long as all `ClusterConfig` values contain the same configuration data.

If the cluster session is disconnected due to a network time-out or network failure, a new session will be created automatically to reconnect to the cluster if possible.
A temporary failure to connect to the cluster will result in temporarily stopping the emission or consumption of any DMs, while local (non-distributed) reactions will still proceed normally.

## Emitting a new distributed molecule

When any code in a DCM peer (within a reaction or outside reactions) emits a new DM, the DM must be already bound to some reaction site locally defined in the DCM peer. 
The newly emitted DM is first sent to that reaction site using the private method `emitDistributed()`.

The ZooKeeper client will attempt to emit the DM.
It is assumed that the Zookeeper client maintains a network connection to the cluster at all times.
If the network connection is down, the emission of a DM fails with an exception.

There is another situation where the emission of a DM can fail.
A DCM peer emits DM in two different circumstances:

1. The DM is emitted as a result of a reaction that consumed other DMs as input (a distributed reaction).
2. The DM is emitted outside reactions, or in a reaction that does not consume any DMs as input.

In the first case, the reaction's inputs have been consumed within a certain cluster session.
If this session expires, the DCM will decide that the current DCM peer has failed to complete the reaction.
In that case, the current DCM peer should not emit DMs that are products of the reaction.

To implement this, the DCM peer will check the current session ID before emitting any DMs from the "outgoing" multiset.
If the current session ID differs from that of the reaction just finished, all DMs from the "outgoing" multiset will be cleared. 
Otherwise, the DMs are emitted into the cluster.

In the second case, the current DCM peer is free to emit the DM.

Therefore, there are two ways of emitting a DM: with restriction to a specified cluster session ID, and without a specified session ID.

If the connection to the cluster is up, the ZooKeeper client will send the DM to the cluster, and if successful, clear the DM from the "outgoing" multiset. 
If the connection fails at that time, the same logic applies:
The DMs are emitted only if the current cluster session ID is the same as the session ID specified by the emission request.

The current cluster session ID is stored within the thread-local data of the thread that runs a reaction.
In this way, the method `emitDistributed` can verify that the cluster session has not been lost. 

## New data available

New DMs can become available on the cluster site in two situations:

- After explicitly emitting a DM (either within a reaction or outside reactions), the DM becomes available for participating DCM peers to consume.
- After a network failure or other failure in a DCM peer that previously consumed some DM from the cluster site and started a reaction, the DCM determines that the DCM peer failed to complete the reaction; the DCM then returns the affected DMs automatically to the cluster site, making them again available.

The DCM peer receives a "new data" notification each time a new distributed molecule becomes available on the cluster.
A "new data" notification is also generated at the beginning of a cluster session.

The content of the "new data" notification is the list of DM emitters that have new data available on the cluster site.
The DCM peer automatically identifies the local DM emitters corresponding to these DMs.

The DCM peer then determines the list of reaction sites that consume any of the newly available DMs.
The "new data" notification is forwarded to each of these reaction sites.

## Reaction scheduling for DM inputs

Each reaction site attempts to schedule new reactions whenever a new molecule is emitted into that site,
or whenever a "new data" notification arrives.

Each RS's reaction scheduler is single-threaded, and so the local molecule multisets do not need any locking.
However, if the RS consumes any distributed molecules as inputs, a distributed locking mechanism will be used,
so that no DMs are removed from the cluster site while a given DCM peer is reading the list of the available DMs or consuming them.

If the RS fails to connect to the cluster and to establish a distributed lock on the relevant DMs, reactions consuming these DMs will not be scheduled.
Reactions consuming solely local molecules will continue to be scheduled if possible.
However, a failure to establish a distributed lock may take a long time to be detected.
During this time, the RS scheduler will be blocked, and no other reactions (including reactions consuming solely local molecules) will be scheduled until the distributed lock is established or a network failure is detected.
To avoid such delays in scheduling solely-local reactions, we could separate solely-local reactions into a separate reaction site that has no bound DMs.  

New DMs may be emitted while a DCM peer is reading the available DMs; the new DMs may not be immediately visible to the DCM peer,
but this will not reduce the number of possibly scheduled reactions.
New DMs will generate another "new data" notification, which will cause them to be examined at a later time.

Each distributed reaction site is identified by a unique hash.
The hash of an RS depends on the entire reaction site's source code (ignoring the order of declaration of reactions and input molecules),
as well as on the names and types of all input molecules bound to that RS.

In ZooKeeper's virtual filesystem, the information about available DMs is stored as files under paths corresponding to each molecule's site index in the RS.
To illustrate the directory structure, assume that two reaction sites have hashes `1a2b34cd` and `5678ef09` and consume 3 input DMs each,
and that two copies of each DM is available.


```
DCM/
   1a2b34cd/
           lock/
           dm-0/
               val-0
               val-1
           dm-1/
               val-0
               val-1
           dm-2/
               val-0
               val-1
   5678ef09/
           lock/
           dm-0/
               val-0
               val-1
           dm-1/
               val-0
               val-1
           dm-2/
               val-0
               val-1
```

The RS's reaction scheduler will request an exclusive distributed lock on the reaction site's hash.
In this way, different RS's (running on any DCM peers) can schedule their reactions concurrently,
but input DMs for each RS are locked until that RS's scheduler decides what reactions to start (which may withdraw some of these DMs).

The serialized data values are stored as the content of the files `val-0`, `val-1`, etc. 

All data carried by DMs is serialized using the [Chill](https://github.com/twitter/chill) library.

The distributed lock is implemented by creating files in the subdirectory `lock` of each reaction site's directory.
The lock is specific to the cluster session ID of the given DCM peer; if the session expires, the lock is abandoned and has to be requested again in a new cluster session.

Once the RS scheduler finds the required input DMs for a reaction, the lock is released.
Other DCM peers may then begin to look for their required input DMs. 

## Starting a reaction

Consider a reaction that consumes both local molecules and DMs.
To determine whether this reaction can start, the DCM peer first checks that the local input molecules are available and then continues to look for distributed molecules by acquiring a distributed lock and downloading the available molecule values.
Once the required input DMs are found, the DCM peer will mark these DMs as consumed and will start a reaction running on its local JVM.

To "mark a DMs as consumed" means to add an ephemeral child node like this,

```
DCM/1a2b34cd/dm-0/val-0/consumed-XXXX
```

where `XXXX` corresponds to the cluster session ID of the DCM peer.

If a DM node such as `val-0` has a child, it means the DM is not currently available for consumption.
Other DCM peers may not consume `dm-0/val-0` when they look for available DMs. 

The ephemeral child node `consumed-XXXX` will automatically disappear if the cluster session expires.
This means that the DCM peer failed to complete the reaction, crashed, or got disconnected by network long enough for the cluster session to break.
In this way, the cluster will automatically mark the molecule `dm-0/val-0` as available, and another DCM peer may consume that molecule later.

When the reaction starts by consuming one or more DMs, the cluster session ID must be stored in the runtime thread that runs the reaction. 

## Completing a reaction

The DCM follows the convention that a distributed reaction is successful if it finished without exceptions.
If a reaction failed for any reason, its input molecules should be restored as again available in the cluster, rolling back the consumption step.
"Complex transactions" (rolling back more than one reaction) are not supported by the DCM because, in general, the chemical program has no single, clearly defined sequence of reactions that would automatically follow a given reaction and could be considered as a "complex transaction" together with a given reaction.
Therefore, the DCM treats each individual reaction as a single "transaction".

When a reaction completes successfully, the DCM peer needs to notify the cluster that the input molecules are irrevocably consumed.
In the example above, the node `dm-0/val-0` must be now deleted, together with the ephemeral child node `consumed-XXXX`.
The current DCM peer does this via its cluster session.

If this last step (deletion of the molecule node) fails because of network failure, the ephemeral child node will be deleted by ZooKeeper while the molecule node remains.
The molecule node will remain available for other DCM peers to consume.
In this way, the cluster will know that the reaction failed.

The absence of the ephemeral child node will be detected the next time the DCM peer connects to the cluster, which should signal to the current DCM peer that another DCM peer might have started another copy of the same reaction.
Thus, any effects of this reaction must be undone or ignored.
A special "clean-up" step might be executed in that case.

When a reaction fails to complete due to an exception, the DCM peer may decide to abandon attempts to run this reaction.
It will then delete the ephemeral child node `consumed-XXXX`, to let the cluster know that the molecule should be considered available.

If the reaction has more than one input molecules, the node operations must be executed transactionally.
