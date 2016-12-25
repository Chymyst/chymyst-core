# Patterns of concurrency

To get more familiar with programming the chemical machine, let us now implement a number of simple concurrent programs.
These programs are somewhat abstract and are chosen to illustrate various patterns of concurrency that are found in real software.

Allen B. Downey's [The little book of semaphores](http://greenteapress.com/semaphores/LittleBookOfSemaphores.pdf) lists many concurrency "puzzles" that he solves in Python using semaphores.
In this and following chapter, we will use the chemical machine to solve Downey's concurrency puzzles as well as some other problems.

Our approach will be deductive: we start with the problem and reason logically about the molecules and reactions that are necessary to solve it.
Eventually we deduce the required chemistry and implement it declaratively in `JoinRun`/`Chymyst`.

## Background job

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
  val startJobMolecule = m[Unit] // Declare a new emitter.

  site (
    go { case startJobMolecule(_) =>
      val result = closure()
      finished(result) 
    }
  )

  startJobMolecule // Return the new emitter.
}

```

The `finished` molecule should be bound to another reaction site.

Another implementation of the same idea will put the `finished` emitter into the molecule value, together with the closure that needs to be run.

However, we lose some polymorphism since Scala values cannot be parameterized by types.
The `startJobMolecule` cannot have type parameters and has to accept `Any` as a type:

```scala
val startJobMolecule = new M[(Unit => Any, M[Any])]

site (
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
  val startJobMolecule = m[(Unit => R, M[R])]

  site (
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

The chemical machine can block something indefinitely only when we emit a blocking molecule whose consuming reaction never starts.
We can prevent a reaction from starting only if some input molecule for that reaction is not present in the soup.
Therefore we wil make a blocking molecule `waiting_for` that reacts with another, non-blocking molecule `godot`; but `godot` never appears.

We also need to make sure that the molecule `godot()` is never emitted into the soup.
To achieve this, we will declare `godot` locally within the scope of `wait_forever()`, where we _emit nothing_ into the soup.

```scala
def wait_forever(): B[Unit, Unit] = {
  val godot = m[Unit]
  val waiting_for = b[Unit, Unit]

  site ( go { case  waiting_for(_, r) + godot(_) => r() } )
  
  // We do not emit `godot` here, which is the key to preventing this reaction from starting.
  
  waiting_for // Return the emitter.
}

```

The function `wait_forever()` will create and return a new blocking molecule emitter that, when called, will block forever, never returning any value.
Here is example usage:

```scala
val never = wait_forever() // Declare a new blocking emitter of type B[Unit, Unit].

never.timeout(1 second)() // this will time out in 1 seconds
never() // this will never return

```

## Waiting until `n` jobs are finished

A frequently used pattern is to start `n` concurrent jobs and wait until all of them are finished.

Suppose that we have started `n` jobs and each job, when done, will emit a non-blocking molecule `done()`.
We would like to implement a blocking molecule `all_done()` that will block until `n` molecules `done()` are emitted.

To begin reasoning about the necessary molecules and reactions, consider that `done()` must react with some other molecule that keeps track of how many `done()` molecules remain to be seen.
Call this other molecule `remaining(k)` where `k` is an integer value showing how many `done()` molecules were already seen.
The reaction should have the form `done() + remaining(k) => ...`, and it is clear that the reaction should consume `done()`, otherwise the reaction will start with it again.
So the reaction will look like this:

```scala
val done = m[Unit]
val remaining = m[Int]

site(
  go { done() + remaining(k) => remaining(k-1) }
)

remaining(n) // Emit the molecule with value `n`,
// which is the initial number of remaining `done()` molecules.

```

Now, it remains to implement waiting until all is done.
The blocking molecule `all_done()` should start its reaction only when we have `remaining(0)` in the soup.
Therefore, the reaction is

```scala
val all_done = b[Unit,Unit]

go { all_done(_, reply) + remaining(0) => reply() }

```

Since this reaction consumes `remaining`, it should be declared at the same reaction site as the `done + remaining => ...` reaction.

The complete code is

```scala
val done = m[Unit]
val remaining = m[Int]
val all_done = b[Unit,Unit]

site(
  go { done() + remaining(k) if k>0 => remaining(k-1) }, // Adding a guard to be safe.
  go { all_done(_, reply) + remaining(0) => reply() }
)

remaining(n) // Emit the molecule with value `n`,
// which is the initial number of remaining `done()` molecules.

```

Adding a guard `if k>0` will prevent the first reaction from running once we consume the expected number of `done()` molecules.
This allows the user to emit more `done()` molecules than expected without disrupting the logic.

### Refactoring into a library

Consider what is required in order to refactor this functionality into a library.
We would like to have a library function call such as `make_all_done()` that creates the `all_done()` molecule for us.

The library function will need to declare a new reaction site.
The `done()` molecule is an input molecule at the new reaction site.
Therefore, this molecule cannot be already defined before we use the library call.
The call to `make_all_done()` will have to return _both_ a new `done()` molecule and a new `all_done()` molecule.
The user of the library will then need to emit the `done()` molecule at the end of each job.

Refactoring a piece of chemistry into a library call is done by declaring the same molecules and reactions as we did above,
all within the scope of a function.
The function should then return just the new molecule emitters that the library user will need to use, but no other molecule emitters:

```scala
def make_all_done(n: Int): (E, EE) = {
  val done = m[Unit]
  val remaining = m[Int]
  val all_done = b[Unit,Unit]
  
  site(
    go { done() + remaining(k) if k>0 => remaining(k-1) }, // Adding a guard to be safe.
    go { all_done(_, reply) + remaining(0) => reply() }
  )
  
  remaining(n)
  
  (done, all_done)
}

```

Note that we declared the molecule types to be `E` and `EE`.
These are the types created by the `m[Unit]` and `m[Unit,Unit]` macro calls.
The class `E` is a refinement of the class `M[Unit]`, and `EE` is a refinement `M[Unit,Unit]`.
The refinements are pure syntactic sugar: they are needed in order to permit the syntax `done()` and `all_done()` without a compiler warning about the missing `Unit` arguments.
Without them, we would have to write `done(())` and `all_done(())`.

## Waiting until `n` jobs are finished: blocking calls

A variation on the same theme is to detect when `n` jobs are finished, given that each job will emit a _blocking_ molecule `done()` at the end.

TODO

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
site (
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

  site (
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

### Providing access tokens

It is often needed to regulate access to resource _R_ by tokens.
We will now suppose that `doWork()` requires a token, and that we only have a limited set of tokens.
Therefore, we need to make sure that

- each process that wants to call `doWork()` receives a token from the token set;
- if all tokens are taken, calls to `doWork()` by other processes are blocked until more tokens become available.

To implement this, we can use the `n`-access restriction on the worker reaction.
We just need to make sure that every worker reaction receives a token that enables it to `doWork()`, and that it returns the token when the access is no longer needed. 

Since the worker reaction already consumes the `access()` molecule, it is clear that we can easily pass the token as the value carried by `access()`.
We just need to change the type of `access` from `M[Unit]` to `M[TokenType]`, where `TokenType` is the type of the value that we need (which could be a string, a thread, a resource handle, etc.). 

Here is the modified code:

```scala
def wrapWithAccessTokens[T, TokenType](tokens: Set[TokenType], doWork: TokenType => T): () => T = {
  val access = m[TokenType]
  val request = b[Unit, T]

  site (
    go { case access(token) + request(_, reply) => reply(doWork(token)); access(token) }
  )

  tokens.foreach(access) // Emit `tokens.size` copies of `access(...)`, putting a token on each molecule.
  val result: () => T = () => request()
  result
}
// Example usage:
val doWorkWithTwoAccesses = wrapWithAccessTokens(Set("token1", "token2"), t => println(s"do work with token $t"))
// Now `doWork()` will be called by at most 2 processes at a time.

// ... start a new process, in which:
doWorkWithTwoAccesses()

```

When concurrent processes emit `request()` molecules, only at most `n` processes will actually do work on the resource at any one time.
Each process will be assigned a token out of the available set of tokens.

## Concurrent critical sections, or `Object.synchronized`

A "critical section" is a portion of code that cannot be safely called from different processes at the same time.
We will now implement the following requirements:

- The calls `beginCritical()` and `endCritical()` can be made in any reaction, in this order.
- The code between these two calls can be executed only by one reaction at a time, among all reactions that call these functions.
- A reaction that calls `beginCritical()` will be blocked if another reaction already called `beginCritical()` but did not yet call `endCritical()`, and will be unblocked only when that other reaction calls `endCritical`. 

This functionality is similar to `Object.synchronized`, which provides synchronized access to a block of reentrant code.
In our case, the critical section is delimited by two function calls `beginCritical()` and `endCritical()` are separate function calls that can be made at any two points in the code -- including `if` expressions or inside closures -- which is impossible with `synchronized` blocks.
Also, the `Object.synchronized` construction identifies the synchronized blocks by an `Object` reference: different objects will be responsible for synchronizing different blocks of reentrant code.
What we would like to allow is _any_ code anywhere to contain any number of critical sections identified by the same `beginCritical()` call.

How can we implement this functionality using the chemical machine?
Since the only way to communicate between reactions is to emit molecules, `beginCritical()` and `endCritical()` must be molecules.
Clearly, `beginCritical` must be blocking while `endCritical` does not have to be; so let us make `endCritical` a non-blocking molecule.

What should happen when a process emits `beginCritical()`?
We must enforce a single-process access to the critical section.
In other words, a reaction that consumes `beginCritical()` should only start one at a time even if several `beginCritical` molecules are available.
Therefore, this reaction must also require another input molecule, say `access()`, of which we will only have a single copy emitted into the soup:

```scala
val beginCritical = b[Unit, Unit]
val access = m[Unit]

site(
  go { case beginCritical(_, reply) + access(_) => ???; reply(); ??? }
)
access() // Emit only one copy.

```

Just as in the case of single-access resource, we have guaranteed that `beginCritical()` blocks if called more than once.
All we need to do is insure that there is initially a single copy of `access()` in the soup, and that no further copies of `access()` are ever emitted.

Another requirement is that emitting `endCritical()` must end whatever `beginCritical()` began, but only if `endCritical()` is emitted by the _same reaction_.
If a different reaction emits `endCritical()`, access to the critical section should not be granted.
Somehow, the `endCritical()` molecules must be different when emitted by different reactions (however, `beginCritical()` must be the same for all reactions, or else there can't be any contention between them).

Additionally, we would like it to be impossible for any reaction to call `endCritical()` without first calling `beginCritical()`.

One way of achieving this is to make `beginCritical()` return a value that enables us to call `endCritical()`.
For instance, `beginCritical()` could actually create a new, unique emitter for `endCritical()`, and return that emitter:

```scala
val beginCritical = b[Unit, M[Unit]]
val access = m[Unit]

site (
  go { case beginCritical(_, reply) + access(_) =>
    val endCritical = m[Unit] // Declare a new emitter.
    site (
      go { case endCritical(_) => ??? }
    )
    reply(endCritical) // beginCritical() returns the new emitter.
  }
)
access() // Emit only one copy.

```

Since `endCritical` and its reaction site are defined within the local scope of the reaction that consumes `beginCritical()`, each such reaction will create a _chemically unique_ new molecule that no other reactions can emit.
This will guarantee that reactions cannot end the critical section for other reactions.

Also, since the `endCritical` emitter is created by `beginCritical()`, we cannot possibly call `endCritical()` before calling `beginCritical()`!
So far, so good.

It remains to make `endCritical()` do something useful when called.
The obvious thing is to make it emit `access()`.
The presence of `access()` in the soup will restore the ability of other reactions to enter the critical section.

The code then looks like this:

```scala
val beginCritical = b[Unit, M[Unit]]
val access = m[Unit]

site (
  go { case beginCritical(_, reply) + access(_) =>
    val endCritical = m[Unit] // Declare a new emitter.
    site (
      go { case endCritical(_) => access() }
    )
    reply(endCritical) // beginCritical() returns the new emitter.
  }
)
access() // Emit only one copy.

// Example usage:
val endCritical = beginCritical()

???... // The code of the critical section.

endCritical() // End of the critical section.

```

This implementation works but has a drawback: the user can call `endCritical()` multiple times.
This will emit multiple `access()` molecules and break the logic of the critical section functionality.

To fix this problem, let is think about how we could prevent `endCritical()` from starting its reaction after the first time it did so.
The only way to prevent a reaction from starting is to omit an input molecule.
Therefore, we need to introduce another molecule (say, `beganOnce`) as input into that reaction.
The reaction will consume that molecule and never emit it again.

```scala
val beginCritical = b[Unit, M[Unit]]
val access = m[Unit]

site (
  go { case beginCritical(_, reply) + access(_) =>
    val endCritical = m[Unit] // Declare a new emitter.
    val beganOnce = m[Unit]
    site (
      go { case endCritical(_) + beganOnce(_) => access() }
    )
    beganOnce() // Emit only one copy.
    reply(endCritical) // beginCritical() returns the new emitter.
  }
)
access() // Emit only one copy.

// Example usage:
val endCritical = beginCritical()

???... // The code of the critical section.

endCritical() // End of the critical section.

endCritical() // This has no effect because `beganOnce()` is not available any more.

```

As before, we can easily modify this code to support multiple (but limited) concurrent entry into critical sections or token-based access. 

### Refactoring into a library

We would like to be able to create new, unique `beginCritical()` molecules on demand, so that we could have multiple distinct critical sections in our code.

For instance, we could declare two critical sections and use them in three reactions that could run concurrently like this: 

```scala
val beginCritical1 = newCriticalSectionMarker()
val beginCritical2 = newCriticalSectionMarker()

```

| Reaction 1 | |  Reaction 2 | | Reaction 3 |
|---|---|---|---|---|
| `beginCritical1()` | | ... | | `beginCritical2()` |
| `beginCritical2()` | | ... | | ... |
| (blocked by 3) | | `beginCritical1()` | | ... |
| (starts running) | | (blocked by 1) | | `endCritical2()` |
| ... | |  (still blocked)| | `beginCritical2()` |
| `endCritical1()` | | (starts running) | | (blocked by 1) |
| `endCritical2()` | | ... | | (starts running) |
| ... | | ... | | ... |
| ... | | `endCritical1()` | | `endCritical2()` |

The result should be that we can create any number of critical sections that work independently of each other.

In order to package the implementation of the critical section into a library function `newCriticalSectionMarker`, we simply declare the chemistry in the local scope of that function and return the molecule emitter:

```scala
def newCriticalSectionMarker(): B[Unit, M[Unit]] = {

  val beginCritical = b[Unit, M[Unit]]
  val access = m[Unit]

  site (
    go { case beginCritical(_, reply) + access(_) =>
      val endCritical = m[Unit] // Declare a new emitter.
      val beganOnce = m[Unit]
      site (
        go { case endCritical(_) + beganOnce(_) => access() }
      )
      beganOnce() // Emit only one copy.
      reply(endCritical) // beginCritical() returns the new emitter.
    }  
  )
  access() // Emit only one copy.

  beginCritical // Return the new emitter.  
}

// Example usage:
val beginCritical1 = newCriticalSectionMarker()
// Now we can pass the `beginCritical1` emitter value to several reactions.
// Suppose we are in one of those reactions:
val endCritical = beginCritical1()

???... // The code of the critical section 1.

endCritical() // End of the critical section.

```
 
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
After that, all `n` waiting processes become unblocked and proceed concurrently.

This problem is similar to the rendezvous with two participants that we considered before.
To simplify the problem, we assume that no data is exchanged -- this is a pure synchronization task.

Let us try to generalize the implementation of the rendezvous from 2 participants to `n`.
Since emitters are values, we could define `n` different emitters `begin1`, ..., `begin_n`, `barrier1`, ..., `barrier_n` and so on.
We could store these emitters in an array and also define an array of corresponding reactions.

However, we notice a problem in the reaction that performs the rendezvous:

```scala
go { case barrier1(x1, reply1) + barrier2(x2, reply2) => ... }

```

Generalizing this reaction straightforwardly to `n` participants would now require a reaction with `n` input molecules `barrier1`, ..., `barrier_n`.
In the chemical machine, input molecules to each reaction must be defined statically.
Since `n` is a run-time parameter, we cannot generalize to `n` in this way.


## Cigarette smokers

The "Cigarette smokers" problem is to implement four concurrent processes that coordinate assembly line operations to manufacture cigarettes with the 
workers smoking the individual cigarettes they manufacture. One process represents a supplier of ingredients on the assembly line, which we may call the 
pusher. The other processes are three smokers, each having an infinite supply of only one of the three required ingredients, which are matches, paper, and 
tobacco. We may give names to the smokers/workers as Keith, Slash, and Jimi. 

The pusher provides at random time intervals one unit each of two required ingredients (for example matches and paper, or paper and tobacco). The pusher is not allowed to coordinate with the smokers to use knowledge of which smoker 
needs which ingredients, he just supplies two ingredients at a time. We assume that the pusher has an infinite supply of the three 
ingredients available to him. The real life example is that of a core operating systems service having to schedule and provide limited distinct resources to 
other services where coordination of scarce resources is required.
 
Each smoker selects two ingredients, rolls up a cigarette using the third ingredient that complements the list, lits it up and smokes it. It is necessary
 for the smoker to finish his cigarette before the pusher supplies the next two ingredients (a timer can simulate the smoking activity). We can think of the 
 smoker shutting down the assembly line operation until he is done smoking.


We model the processes as follows:

| Supplier   | | Smoker 1   | |  Smoker 2    | |  Smoker 3 |
| --- | --- | --- | --- | --- | --- |  --- |
| select 2 random ingredients | | pick tobacco and paper | | pick tobacco and matches | | pick matches and paper |


Let us now figure out the chemistry that will solve this problem. We can think of the problem as a concurrent producer consumer queue with three competing 
consumers and the supplier simply produces random pairs of ingredients into the queue. For simplicity, we assume the queue has capacity 1, which is an 
assumption in the statement of the problem (smoker shuts down factory operation while he takes a smoke break, thus pausing the pusher).
 
It is important to think of a suitable data model to capture the state of the world for the problem, so we need to know when to stop and count how many 
cycles we go through, if we want to stop the computation. It may be useful to keep track of how many ingredients have been shipped or consumed but this does 
not look to be important for a minimal solution. We include inventory tracking because of some logging we do to represent the concurrent activities more 
explicitly; this inventory tracking does add a bit of complexity.

For counting, we will use a distinct molecule `count` dedicated just to that and emit it initially with a constant number. We also use a blocking molecule 
`check` that we emit when we reach a `count` of 0. This approach is same as in several other examples discussed here.
```scala
    val count = m[Int]
    val check = new EE("check") 

    site(tp) ( // reactions
          go { case pusher(???) + count(n) if n >= 1 => ??? // the supply reaction TBD
                 count(n-1) // let us include this decrement now.
             },
          go { case count(0) + check(_, r) => r() }  // note that we use mutually exclusive conditions on count in the two reactions.
    )
    // emission of initial molecules in chemistry follows
    // other molecules to emit as necessary for the specifics of this problem
    count(supplyLineSize) // if running as a daemon, we would not use the count molecule and let the example/application run for ever.
    check()

```

We now introduce one molecule for each smoker and their role should be symmetrical while capturing the information about their ingredient input requirements.
 Let us give names to smoker molecules as `Keith`, `Slash`, and `Jimi`. Let us assign one molecule per ingredient: `paper`, `matches`, and `tobacco`. This 
 will represent the last three reactions. We need to emit the molecules for `Keith`, `Slash`, and `Jimi` on start up, which can be combined (`Keith(()) + Slash(()) + Jimi(())`).

Here, we write up a helper function `enjoyAndResume` that is a refactored piece of code that is common to the three smokers, who, when receiving ingredients, make the 
 cigarette, smoke it while taking a break off work, shutting down the operations and then resuming operation to `pusher` when done, which is required as the 
 smoker must notify the `pusher` when to resume. The smoking break is a simple waste of time represented by a sleep. The smoker molecule must re-inject 
 itself once done to indicate readiness of the smoker to get back to work to process the next pair of ingredients. 
 
 Notice here that we capture a state of the shipped inventory from the ingredient molecules, all of which carry 
 the value of the inventory and echo it back to the pusher so that he knows where he is at in his bookkeeping; the smokers collaborate and don't lie so 
 simply echo back the inventory as is (note that this is not necessary if we are not interested in tracking down how many ingredients have been used in 
 manufacturing).
```scala
    def smokingBreak(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)
    def enjoyAndResume(s: ShippedInventory) = {
      smokingBreak()
      pusher(s)
    }
   site(tp) ( // reactions
     // other reactions ...
      go { case Keith(_) + tobacco(s) + matches(_) => enjoyAndResume(s); Keith() },
      go { case Slash(_) + tobacco(s) + paper(_) => enjoyAndResume(s); Slash() },
      go { case Jimi(_) + matches(s) + paper(_) => enjoyAndResume(s); Jimi()}
   )
   // other initial molecules to be emitted
   Keith(()) + Slash(()) + Jimi(())

```
Now, we need the `pusher` molecule to generate a pair of ingredients randomly at time intervals. Paying attention to the statement of the problem, we notice 
that he needs to wait for a smoker to be done, hence as stated before, we simply need to emit the `pusher` molecule from the smoker reactions (a signal in 
conventional 
terminology) and the `pusher` molecule should be emitted on start up and should respond to the count molecule to evaluate the deltas in the 
`ShippedInventory`; the active `pusher` can be thought of as representing an active factory so we must emit its molecule on start up.
 
 We represent the shipped inventory as a case class with a count for each ingredient, we call it 
`ShippedInventory`. We integrate counting as previously discussed, introduce the random selection of ingredient pairs and emit the molecules for the pair of ingredients.
```scala
  case class ShippedInventory(tobacco: Int, paper: Int, matches: Int)
  site(tp) (
      go { case pusher(ShippedInventory(t, p, m)) + count(n) if n >= 1 =>
        scala.util.Random.nextInt(3) match { // select the 2 ingredients randomly
          case 0 =>
            val s = ShippedInventory(t+1, p, m+1)
            tobaccoShipment(s)
            matchesShipment(s)
          case 1 =>
            val s =  ShippedInventory(t+1, p+1, m)
            tobaccoShipment(s)
            paperShipment(s)
          case _ =>
            val s = ShippedInventory(t, p+1, m+1)
            matchesShipment(s)
            paperShipment(s)
        }
        count(n-1)
      }
  )
  count(supplyLineSize) 
  pusher(ShippedInventory(0,0,0))
  // other initial molecules to be emitted (the smokers and check)
  
```

The final code looks like this:

```scala
   val supplyLineSize = 10
    def smokingBreak(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)

    case class ShippedInventory(tobacco: Int, paper: Int, matches: Int)
    // this data is only to demonstrate effects of randomization on the supply chain and make content of logFile more interesting.
    // strictly speaking all we need to keep track of is inventory. Example would work if pusher molecule value would carry Unit values instead.

    val pusher = m[ShippedInventory] // pusher means drug dealer, in classic Comp Sci, we'd call this producer or publisher.
    val count = m[Int]
    val KeithInNeed = new E("Keith obtained tobacco and matches to get his fix") // makes for more vivid tracing, could be plainly m[Unit]
    val SlashInNeed = new E("Slash obtained tobacco and matches to get his fix") // same
    val JimiInNeed = new E("Jimi obtained tobacco and matches to get his fix") // same

    val tobaccoShipment = m[ShippedInventory] // this is not particularly elegant, ideally this should carry Unit but pusher needs to obtain current state
    val matchesShipment = m[ShippedInventory] // same
    val paperShipment = m[ShippedInventory] // same

    val check = new EE("check") // blocking Unit, only blocking molecule of the example.

    val logFile = new ConcurrentLinkedQueue[String]

    site(tp) (
      go { case pusher(ShippedInventory(t, p, m)) + count(n) if n >= 1 =>
        logFile.add(s"$n,$t,$p,$m") // logging the state makes it easier to see what's going on, curious user may put println here instead.
        scala.util.Random.nextInt(3) match { // select the 2 ingredients randomly
          case 0 =>
            val s = ShippedInventory(t+1, p, m+1)
            tobaccoShipment(s)
            matchesShipment(s)
          case 1 =>
            val s =  ShippedInventory(t+1, p+1, m)
            tobaccoShipment(s)
            paperShipment(s)
          case _ =>
            val s = ShippedInventory(t, p+1, m+1)
            matchesShipment(s)
            paperShipment(s)
        }
        count(n-1)
      },
      go { case count(0) + check(_, r) => r() },

      go { case KeithInNeed(_) + tobaccoShipment(s) + matchesShipment(_) =>
        smokingBreak(); pusher(s); KeithInNeed()
      },
      go { case SlashInNeed(_) + tobaccoShipment(s) + paperShipment(_) =>
        smokingBreak(); pusher(s); SlashInNeed()
      },
      go { case JimiInNeed(_) + matchesShipment(s) + paperShipment(_) =>
        smokingBreak(); pusher(s); JimiInNeed()
      }
    )

    KeithInNeed(()) + SlashInNeed(()) + JimiInNeed(())
    pusher(ShippedInventory(0,0,0))
    count(supplyLineSize) // if running as a daemon, we would not use count and let the example/application run for ever.

    check()
```

There is a harder, more general treatment of the cigarette smokers problem, which has the `pusher` in charge of the rate of availability of ingredients, not 
having to wait for smokers, which the reader may think of using a producer-consumer queue with unlimited buffer in classic treatment of the problem. The 
solution is provided in code. Let us say that the change is very simple, we just need
 to have pusher emit the pusher molecule instead
 of the smokers doing so.  The pausing in the assembly line needs to be done within the `pusher` reaction, otherwise it is the same solution.
### Refactoring into a library
