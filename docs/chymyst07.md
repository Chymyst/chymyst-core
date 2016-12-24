# Patterns of concurrency

To get more familiar with programming the chemical machine, let us now implement a number of simple concurrent programs.
These programs are somewhat abstract and are chosen to illustrate various patterns of concurrency that are found in real software.

Allen B. Downey's [The little book of semaphores](http://greenteapress.com/semaphores/LittleBookOfSemaphores.pdf) lists many concurrency "puzzles" that he solves in Python using semaphores.
In this and following chapter, we will use the chemical machine to solve Downey's concurrency puzzles as well as some other problems.

Our approach will be deductive: we start with the problem and reason about the molecules and reactions that are necessary to solve it.
Eventually we deduce the required chemistry and implement it declaratively in `JoinRun`/`Chymyst`.

## Background jobs

A basic asynchronous task is to start a long background job and get notified when it is done.

A chemical model is easy to invent: the reaction needs no data to start (the calculation can be inserted directly in the reaction body).
So we define a reaction with a single non-blocking input molecule that carries a `Unit` value.
The reaction will consume the molecule, do the long calculation, and then emit a `finished(...)` molecule that carries the result value on it.

A convenient implementation is to define a function that will return an emitter that starts the job.

```scala
/**
* Prepare reactions that will run a closure and emit a result upon its completion.
*
* @tparam R The type of result value
* @param closure The closure to be run
* @param finished A previously bound non-blocking molecule to be emitted when the calculation is done
* @return A new non-blocking molecule that will start the job
*/
def submitJob[R](closure: Unit => R, finished: M[R]): M[R] = {
  val startJobMolecule = new M[Unit]

  site( go { case startJobMolecule(_) =>
    val result = closure()
    finished(result) }
   )

   startJobMolecule
}

```

The `finished` molecule should be bound to another reaction site.

Another implementation of the same idea will put the `finished` emitter into the molecule value, together with the closure that needs to be run.

However, we lose some polymorphism since Scala values cannot be parameterized by types.
The `startJobMolecule` cannot have type parameters and has to accept `Any` as a type:

```scala
val startJobMolecule = new M[(Unit => Any, M[Any])]

site(
  go {
    case startJobMolecule(closure, finished) =>
      val result = closure()
      finished(result)
  }
)

```

A solution to this difficulty is to create a method that is parameterized by type and returns a `startJobMolecule`:

```scala
def makeStartJobMolecule[R]: M[(Unit => R, M[R])] = {
  val startJobMolecule = new M[(Unit => R, M[R])]

  site(
    go {
      case startJobMolecule(closure, finished) =>
        val result = closure()
        finished(result)
   }
  )
  startJobMolecule
}

```

## Waiting forever

Suppose we want to implement a function `wait_forever()` that blocks indefinitely, never returning.

The chemical model is that a blocking molecule `waiting_for` reacts with another, non-blocking molecule `godot`; but `godot` never appears in the soup.

We also need to make sure that the molecule `godot()` is never emitted into the soup.
So we declare `godot` locally within the scope of `wait_forever`, where we'll emit nothing into the soup.

```scala
def wait_forever: B[Unit, Unit] = {
  val godot = m[Unit]
  val waiting_for = b[Unit, Unit]

  site( go { case  waiting_for(_, r) + godot(_) => r() } )
  
  // We do not emit `godot` here, which is the key to preventing this reaction from starting.
  
  waiting_for
}

```

The function `wait_forever` will create and return a new blocking molecule emitter that, when called, will block forever, never returning any value.
Here is example usage:

```scala
val never = wait_forever // Declare a new blocking molecule of type B[Unit, Unit].

never.timeout(1 second)() // this will time out in 1 seconds
never() // this will never return

```

## Control over a shared resource (mutex, multiplex)

### Single access

Suppose we have an application with many concurrent processes and a shared resource _R_ (say, a database server) that should be only used by one process at a time.
Let us assume that there is a certain function `doWork()` that will use the resource _R_ when called.
Our goal is to make sure that `doWork()` is only called by at most one concurrent process at any time.
While `doWork()` is being evaluated, we consider that the resource _R_ is not available.
If `doWork()` is already being called by one process, all other processes trying to call `doWork()` should be blocked until the first `doWork()` is finished and the resource _R_ is again available.

How would we solve this problem using the chemical machine?
Since our only way to control concurrency is by manipulating molecules, we need to organize the chemistry such that `doWork()` is only called when certain molecules are available.
In other words, `doWork()` must be called by a _reaction_ that consumes certain molecules whose presence or absence we will control.
The reaction must be of the form `case [some molecules] => ... doWork() ...`.
Let us call it the "worker reaction".
There must be no other way to call `doWork()` except by starting this reaction.

Processes that need to call `doWork()` will therefore need to emit a certain molecule that the worker reaction consumes.
Let us call that molecule `request`.
The `request` molecule must be a _blocking_ molecule because `doWork()` should be able to block the caller when the resource _R_ is not available.

If the `request` molecule is the only input molecule of the worker reaction, we will be unable to prevent the reaction from starting whenever some process emits a `request` molecule.
Therefore, we need a second input molecule in the worker reaction.
Let us call that molecule `access`.
The worker reaction will then look like this:

```scala
go { case access(_) + request(_, r) => ... doWork() ... }

```

Suppose some process emits a `request` molecule, and suppose this is the only process that does so at the moment.
We should then allow the worker reaction to start and to complete `doWork()`.
Therefore, `access()` must be present at the reaction site: we should have emitted it beforehand.

While `doWork()` is evaluated, the `access()` molecule is absent, so no other copy of the worker reaction can start.
This is precisely the exclusion behavior we need.

When the `doWork()` function finishes, we need to unblock the calling process by replying to the `request` molecule.
Perhaps `doWork()` will return a result value: in that case, we should pass this value as the reply value.
We should also emit the `access()` molecule back into the soup, so that another process will be able to run `doWork`.

After these considerations, the worker reaction becomes

```scala
site(
  go { case access(_) + request(_, reply) => reply(doWork()); access() }
)
access() // Emit just one copy of `access`.

```

As long as we emit just one copy of the `access` molecule, and as long as `doWork()` is not used elsewhere in the code, we will guarantee that at most one process will call `doWork()` at any time.

### Multiple access

What if we now relax the requirement of single access for the resource _R_?
Suppose that now, at most `n` concurrent processes should be able to call `doWork()`.

This can be achieved simply by emitting `n` copies of the `access()` molecule.
The worker reaction remains unchanged.

### Refactoring into a library function

The following code defines a convenience function that wraps `doWork()` and provides single-access or multiple-access restrictions.
This illustrates how we can easily and safely package chemistry into a reusable library.

```scala
def wrapWithAccess[T](allowed: Int, doWork: () => T): () => T = {
  val access = m[Unit]
  val request = b[Unit, T]

  site(
    go { case access(_) + request(_, reply) => reply(doWork()); access() }
  )

  (1 to n).foreach(_ => access()) // Emit `n` copies of `access`.
  val result: () => T = () => request()
  result
}
// Example usage:

val doWorkWithTwoAccesses = wrapWithAccess(2, () => println("do work"))
// Now `doWork()` will be called by at most 2 processes at a time.

// ... start a new process, in which:
doWorkWithTwoAccesses()

```

## Critical section, or `synchronized`



## Rendezvous, or `java.concurrent.Exchanger`

The "rendezvous" problem is to implement two concurrent processes that perform some computations and wait for each other like this:

| Process 1 | |  Process 2 |
| --- | --- | --- |
| `val x1 =` compute something | | `val x2 =` compute something |
| send `x1` to Process 2, wait for reply | | send `x2` to Process 1, wait for reply |
| `val y1 =` what Process 2 computed as its `x2` | | `val y2 =` what Process 1 computed as its `x1` |
| `val z = further_computations_1(y1)` | | `val z = further_computations_2(y2)` |

(This functionality is essentially that of `java.concurrent.Exchanger`.)

Let us now figure out the chemistry that will solve this problem.

The two processes must be reactions (since any computation that runs in the chemical machine is a reaction).
These reactions must start by consuming some initial molecules.
Let us start by defining these molecules and reactions, leaving undefined places for the next steps:

```scala
val begin1 = m[Unit]
val begin2 = m[Unit]

site(
  go { case begin1(_) =>
    val x1 = 123 // some computation
    ??? // send x1 to Process 2 somehow
    val y1 = ??? // receive value from Process 2
    val z = further_computation_1(y1)
   },
  go { case begin2(_) =>
    val x2 = 456 // some computation
    ??? // send x2 to Process 1 somehow
    val y2 = ??? // receive value from Process 1
    val z = further_computation_2(y2)
   }
)
begin1() + begin2() // emit both molecules to enable starting the two reactions

```

So far, so good.
Look at what happens in in Process 1 after `x1` is computed.
The next step is to send the value `x1` to Process 2.
The only way we can send data to any other process is by emitting a molecule.
Therefore, here we must be emitting _some molecule_.

Now, either Process 1 or Process 2 is already running by this time, and so it won't help if we emit a molecule that Process 2 consumes as input.
Therefore, we must emit a new molecule that neither Process 1 nor Process 2 consume as input.

Also note that each process must wait until the other process sends back its value. 
Therefore, the new molecule must be a blocking molecule.

Let's say that each process would emit its own blocking molecule at this point, and let's call these molecules `barrier1` and `barrier2`.
The code will look like this:

```scala
val begin1 = m[Unit]
val begin2 = m[Unit]

val barrier1 = b[Unit,Unit]
val barrier2 = b[Unit,Unit]

site(
  go { case begin1(_) =>
    val x1 = 123 // some computation
    barrier1(x1) 
    ??? // send x1 to Process 2 somehow
    val y1 = ??? // receive value from Process 2
    val z = further_computation_1(y1)
   },
  go { case begin2(_) =>
    val x2 = 456 // some computation
    barrier2(x2)
    ??? // send x2 to Process 1 somehow
    val y2 = ??? // receive value from Process 1
    val z = further_computation_2(y2)
   }
)
begin1() + begin2() // emit both molecules to enable starting the two reactions

```

Now we note that blocking molecules can receive reply values.
Therefore, the call to `barrier1` may receive a reply value.
This is exactly what we need!
Let's make `barrier1` return the value that Process 2 sends, and vice versa.

```scala
val begin1 = m[Unit]
val begin2 = m[Unit]

val barrier1 = b[Int,Int]
val barrier2 = b[Int,Int]

site(
  go { case begin1(_) =>
    val x1 = 123 // some computation
    val y1 = barrier1(x1) // receive value from Process 2 
    val z = further_computation_1(y1)
   },
  go { case begin2(_) =>
    val x2 = 456 // some computation
    val y2 = barrier2(x2) // receive value from Process 1
    val z = further_computation_2(y2)
   }
)
begin1() + begin2() // emit both molecules to enable starting the two reactions

```

At this point, the molecules `barrier1` and `barrier2` are not yet consumed by any reactions.
We now need to define some reaction that consumes these molecules.
It is clear that what we need is a reaction that exchanges the values these two molecules carry.
The easiest solution is to just let these two molecules react with each other.
The reaction will then reply to both of them, exchanging the reply values. 

```scala
go { case barrier1(x1, reply1) + barrier2(x2, reply2) => reply1(x2); reply2(x1) }

```

This reaction could be defined at its own reaction site, since it is the only reaction that will consume `barrier1` and `barrier2`.
(The same is true for the two `begin1` and `begin2` reactions.)
For simplicity, we will keep all reactions in one reaction site.

The final code looks like this:

```scala
val begin1 = m[Unit]
val begin2 = m[Unit]

val barrier1 = b[Int,Int]
val barrier2 = b[Int,Int]

site(
  go { case begin1(_) =>
    val x1 = 123 // some computation
    val y1 = barrier1(x1) // receive value from Process 2 
    val z = further_computation_1(y1)
   },
  go { case begin2(_) =>
    val x2 = 456 // some computation
    val y2 = barrier2(x2) // receive value from Process 1
    val z = further_computation_2(y2)
   },
   go { case barrier1(x1, reply1) + barrier2(x2, reply2) => reply1(x2); reply2(x1) }
)
begin1() + begin2() // emit both molecules to enable starting the two reactions

```

Working test code for the rendezvous problem is in `Patterns01Spec.scala`.

## Rendezvous with `n` participants

Suppose we need a rendezvous with `n` participants: there are `n` processes (where `n` is a run-time value, not known in advance), and each process would like to wait until all other processes reach the rendezvous point.
Ultimately we would like to refactor the code into a library, so that it is easy to use.
