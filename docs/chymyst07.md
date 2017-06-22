<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Patterns of concurrency

To get more intuition about programming the chemical machine, let us now implement a number of simple concurrent programs.
These programs are somewhat abstract and are chosen to illustrate various patterns of concurrency that are found in real software.

Allen B. Downey's [_The little book of semaphores_](http://greenteapress.com/semaphores/LittleBookOfSemaphores.pdf) lists many concurrency "puzzles" that he solves in Python using semaphores.
In this and following chapter, we will use the chemical machine to solve those concurrency puzzles as well as some other problems.

Our approach will be deductive: we start with the problem and reason logically about the molecules and reactions that are necessary to solve it.
Eventually we deduce the required chemistry and implement it declaratively in `Chymyst`.

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

The function `wait_forever()` will create and return a new blocking emitter that, when called, will block forever, never returning any value.
Here is example usage:

```scala
val never = wait_forever() // Declare a new blocking emitter of type B[Unit, Unit].

never.timeout()(1 second) // this will time out in 1 seconds
never() // this will never return

```

## Background jobs

A basic concurrency pattern is to start a long background job and to get notified when the job is finished.

It is easy to come up with a suitable chemistry.
The reaction needs no data to start, and the computation can be inserted directly into the reaction body.
So we define a reaction with a single non-blocking input molecule.
The reaction will consume the molecule, do the long computation, and then emit a `finished(...)` molecule that carries the result value of the computation.

A convenient implementation is to define a function that will return an emitter that starts the job.

```scala
/**
* Prepare reactions that will run a closure
* and emit a result upon its completion.
*
* @tparam R The type of result value
* @param closure  The closure to be run
* @param finished A previously bound non-blocking molecule
*                 to be emitted when the computation is done
* @return A new non-blocking molecule that will start the job
*/
def submitJob[R](closure: () => R, finished: M[R]): M[R] = {
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

However, we lose some polymorphism since Scala values cannot be parameterized by a type.
The `startJobMolecule` cannot have type parameters and so has to carry values of type `Any`:

```scala
val startJobMolecule = new M[() => Any, M[Any])]

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
def makeStartJobMolecule[R]: M[(() => R, M[R])] = {
  val startJobMolecule = m[(() => R, M[R])]

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

## Waiting until `n` jobs are finished: non-blocking calls

A frequently used pattern is to start `n` concurrent jobs and wait until all of them are finished.

Suppose that we have started `n` jobs and each job, when done, will emit a _non-blocking_ molecule `done()`.
We would like to implement a blocking molecule `all_done()` that will block until `n` molecules `done()` are emitted.

To begin reasoning about the necessary molecules and reactions, consider that `done()` must react with some other molecule that keeps track of how many `done()` molecules remain to be seen.
Call this other molecule `remaining(k)` where `k` is an integer value showing how many `done()` molecules were already seen.
The reaction should have the form `done() + remaining(k) => ...`, and it is clear that the reaction should consume `done()`, otherwise the reaction will start with it again.
So the reaction will look like this:

```scala
val done = m[Unit]
val remaining = m[Int]

site(
  go { done(_) + remaining(k) => remaining(k - 1) }
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

Since this reaction consumes `remaining()`, it should be declared at the same reaction site as the `{ done + remaining => ... }` reaction.

The complete code is

```scala
val done = m[Unit]
val remaining = m[Int]
val all_done = b[Unit,Unit]

site(
  go { done(_) + remaining(k) if k > 0 => remaining(k - 1) }, // Adding a guard to be safe.
  go { all_done(_, reply) + remaining(0) => reply() }
)
remaining(n) // Emit the molecule with value `n`,
// which is the initial number of remaining `done()` molecules.

```

Adding a guard (`if k > 0`) will prevent the first reaction from running once we consume the expected number of `done()` molecules.
This allows the user to emit more `done()` molecules than `n`, without disrupting the logic.

### Example usage

How would we use this code?
Suppose we have `n` copies of a reaction whose completion we need to wait for.
At the end of that reaction, we will now emit a `done()` molecule.
After emitting the input molecules for these reactions to start, we call `all_done()` and wait for the completion of all jobs:

```scala
val begin = m[Int]

site(
  go { begin(x) => long_computation(x); done() )}
)
val n = 10000
(1 to n).foreach(begin) // Emit begin(1), begin(2), ..., begin(10000) now.

all_done() // This will block until all reactions are finished.

```

### Refactoring into a function

Consider what is required in order to refactor this chemistry into a reusable function
such as `make_all_done()` that would create the `all_done()` molecule for us.

The function `make_all_done()` will need to declare a new reaction site.
The `done()` molecule is an input molecule at the new reaction site.
Therefore, this molecule cannot be already defined before we perform the call to `make_all_done()`.

We see that the result of the call `make_all_done()` must be the creation of _two_ new molecules: a new `done()` molecule and a new `all_done()` molecule.

The user will then need to make sure that every job emits the `done()` molecule at the end of the job,
and arrange for all the jobs to start.
When it becomes necessary to wait until the completion of all jobs, the user code will simply emit the `all_done()` blocking molecule and wait until it returns.

Refactoring an inline piece of chemistry into a reusable function is generally done in two steps:

- within the function scope, declare the same molecules and reactions as in the previous inline code;
- at the end of the function body, return just the new molecule emitters that the user will need to call, but no other molecule emitters.

Here is the result of this refactoring for our previous code:

```scala
def make_all_done(n: Int): (M[Unit], B[Unit, Unit]) = {
  val done = m[Unit]
  val remaining = m[Int]
  val all_done = b[Unit,Unit]
  
  site(
    go { done(_) + remaining(k) if k > 0 => remaining(k - 1) }, // Adding a guard to be safe.
    go { all_done(_, reply) + remaining(0) => reply() }
  )
  remaining(n)
  
  (done, all_done)
}

```

Let us now use the method `make_all_done()` to simplify the example usage code we had in the previous section:

```scala
val begin = m[Int]
val n = 10000

val (done, all_done) = make_all_done(n)

site(
  go { begin(x) => long_computation(x); done() )}
)

(1 to n).foreach(begin) // Emit begin(1), begin(2), ..., begin(10000) now.

all_done() // This will block until all reactions are finished.

```

### Waiting until `n` jobs are finished: blocking calls

A variation on the same theme is to detect when `n` jobs are finished, given that each job will emit a _blocking_ molecule `done()` at the end.

In the previous section, we encapsulated the functionality of waiting for `n` non-blocking molecules into a function `make_all_done()`.
Let us take the code we just saw for `make_all_done()` and try to modify it for the case when `done()` is a blocking molecule.
The key reaction

```scala
go { done(_) + remaining(k) if k > 0 => remaining(k - 1) }

```

now needs to be modified because we must reply to the `done()` molecule at some point in that reaction:

```scala
go { done(_, r) + remaining(k) if k > 0 => r() + remaining(k - 1) }

```

In this way, we unblock whatever final cleanup the job needs to do after it signals `done()`.
All other code in `make_all_done()` remains unchanged.

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
Our code must be such that the only way to call `doWork()` is by starting this reaction.

Processes that need to call `doWork()` will therefore need to emit a certain molecule that the worker reaction consumes.
Let us call that molecule `request()`.
The `request()` molecule must be a _blocking_ molecule because `doWork()` should be able to block the caller when the resource _R_ is not available.

If the `request()` molecule is the only input molecule of the worker reaction, we will be unable to prevent the reaction from starting whenever some process emits `request()`.
Therefore, we need a _second_ input molecule in the worker reaction.
Let us call that molecule `access()`.
The worker reaction will then look like this:

```scala
go { case access(_) + request(_, r) => ... doWork() ... }

```

Suppose some process emits a `request` molecule, and suppose this is the only process that does so at the moment.
We should then allow the worker reaction to start and to complete `doWork()`.
Therefore, `access()` must be present at the reaction site: we should have emitted it beforehand.

While `doWork()` is evaluated, the `access()` molecule is absent, so no other copy of the worker reaction can start.
This is precisely the exclusion behavior we need.

When the `doWork()` function finishes, we need to unblock the calling process by replying to the `request()` molecule.
Perhaps `doWork()` will return a result value: in that case, we should pass this value as the reply value.
We should also emit the `access()` molecule back into the soup, so that another process will be able to run `doWork()`.

After these considerations, the worker reaction becomes

```scala
site (
  go { case access(_) + request(_, reply) => reply(doWork()) + access() }
)
access() // Emit just one copy of `access`.

```

As long as we emit just one copy of the `access()` molecule, and as long as `doWork()` is not used elsewhere in the code, we will guarantee that at most one process will call `doWork()` at any time.

### Multiple access

What if we need to relax the requirement of single access for the resource _R_?
Suppose that now, at most `n` concurrent processes should be able to call `doWork()`.

This formulation of the shared resource problem will describe, for instance, the situation where a connection pool with a fixed number of connections should be used to access a database server.

The effect of allowing `n` simultaneous reactions to access the resource _R_ can be achieved simply by initially emitting `n` copies of the `access()` molecule.
The worker reaction remains unchanged.
This is how easy it is to program the chemical machine!

### Refactoring into a function

The following code defines a convenience function that wraps `doWork()` and provides single-access or multiple-access restrictions.
This illustrates how we can easily and safely package new chemistry into a reusable function.

```scala
def wrapWithAccess[T](allowed: Int, doWork: () => T): () => T = {
  val access = m[Unit]
  val request = b[Unit, T]

  site (
    go { case access(_) + request(_, reply) => reply(doWork()) + access() }
  )

  (1 to n).foreach(access) // Emit `n` copies of `access`.
  val result: () => T = () => request()
  result
}
// Example usage:
val doWorkWithTwoAccesses = wrapWithAccess(2, () => println("do work"))
// Now `doWork()` will be called by at most 2 processes at a time.

// ... start a new process, in which:
doWorkWithTwoAccesses()

```

### Token-based access

It is often needed to regulate access to resource _R_ by tokens that carry authentication or other information.
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

This functionality is similar to `Object.synchronized`, which provides exclusive synchronized access within otherwise reentrant code.
In our case, the critical section is delimited by two function calls, `beginCritical()` and `endCritical()`, that can be made at any two points in the code — including `if` expressions or inside closures,
which is impossible with `Object.synchronized`.

The `Object.synchronized` construction identifies the synchronized blocks by an `Object` reference:
different objects will be responsible for synchronizing different blocks of reentrant code.
What we would like to allow is _any_ code anywhere to contain any number of critical sections identified by the same `beginCritical()` call.

How can we implement this functionality using the chemical machine?
Since the only way to communicate between reactions is to emit molecules, `beginCritical()` and `endCritical()` must be molecules.
Clearly, `beginCritical` must be blocking while `endCritical` does not have to be; so let us make `endCritical` a non-blocking molecule.

What should happen when a process emits `beginCritical()`?
We must enforce a single-user exclusive access to the critical section.
In other words, only one reaction that consumes `beginCritical()` should be running at any one time, even if several `beginCritical` molecules are available.
Therefore, this reaction must also require another input molecule, say `access()`, of which we will only have a single copy emitted into the soup:

```scala
val beginCritical = b[Unit, Unit]
val access = m[Unit]

site(
  go { case beginCritical(_, reply) + access(_) => ???; reply(); ??? }
)
access() // Emit only one copy.

```

Just as in the case of single-access resource, we can guarantee that `beginCritical()` will block if called more than once.
All we need to do is insure that there is initially a single copy of `access()` in the soup, and that no further copies of `access()` are ever emitted.

Another requirement is that emitting `endCritical()` must end whatever `beginCritical()` began, but only if `endCritical()` is emitted by the _same reaction_.
If a different reaction emits `endCritical()`, no access to the critical section should be granted.
Somehow, the `endCritical()` molecules must be distinct when emitted by different reactions.
However, `beginCritical()` must be the same for all reactions, or else there can't be any contention between them.

Additionally, we would like it to be impossible for any reaction to call `endCritical()` without first calling `beginCritical()`.

One way of achieving this is to make `beginCritical()` return a value that is required for us to call `endCritical()` later.
The simplest such value is the emitter `endCritical` itself.
So let us make `beginCritical()`, which is a blocking call, return the `endCritical` emitter.

To achieve the uniqueness of `endCritical`, let us implement the call to `beginCritical()` in such a way that it creates each time a new, unique emitter for `endCritical()`, and returns that emitter:

```scala
val beginCritical = b[Unit, M[Unit]]
val access = m[Unit]

site (
  go { case beginCritical(_, reply) + access(_) =>
    val endCritical = m[Unit] // Declare a new emitter, unique for this call to `beginCritical`.
    site (
      go { case endCritical(_) => ??? }
    )
    reply(endCritical) // beginCritical() returns the new emitter.
  }
)
access() // Emit only one copy.

```

Since `endCritical()` and its chemistry are defined within the local scope of the reaction that consumes `beginCritical()`,
each such scope will create a _chemically unique_ new molecule that no other reactions can emit.
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

This implementation works but has a drawback: the user may call `endCritical()` multiple times.
This will emit multiple `access()` molecules and break the logic of the critical section functionality.

To fix this problem, let us think about how we could prevent `endCritical()` from starting its reaction after the first time it did so.
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

As before, we can easily modify this code to support multiple (but limited) concurrent entry into critical sections, or to support token-based access. 

### Refactoring into a function

We would like to be able to create new, unique `beginCritical()` molecules on demand, so that we could have multiple distinct critical sections in our code.

For instance, we could declare two critical sections and use them in three reactions that could run concurrently like this: 

```scala
val beginCritical1 = newCriticalSectionMarker()
val beginCritical2 = newCriticalSectionMarker()

```

The result should be that we are able to delimit any number of critical sections that work independently of each other.

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

In order to package the implementation of the critical section into a function `newCriticalSectionMarker()`,
we simply declare the chemistry in the local scope of that function and return the molecule emitter:

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

Let us now look at what happens in Process 1 after `x1` is computed.
The next step at that point is to send the value `x1` to Process 2.
The only way of sending data to another process is by emitting a molecule with a value.
Therefore, here we must be emitting _some molecule_.

Now, either Process 1 or Process 2 is already running by this time, and so it won't help if we emit a molecule that Process 2 consumes as input.
Therefore, we must emit a new molecule that neither Process 1 nor Process 2 consume as input.

Also note that each process must wait until the other process sends back its value. 
Therefore, the new molecule must be a blocking molecule.

Let's say that each process will emit a blocking molecule `barrier()` at the point when it must wait for the other process.
The code will look like this:

```scala
val begin1 = m[Unit]
val begin2 = m[Unit]

val barrier = b[Unit,Unit]

site(
  go { case begin1(_) =>
    val x1 = 123 // some computation
    barrier(x1) 
    ??? // send x1 to Process 2 somehow
    val y1 = ??? // receive value from Process 2
    val z = further_computation_1(y1)
   },
  go { case begin2(_) =>
    val x2 = 456 // some computation
    barrier(x2)
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

val barrier = b[Int,Int]

site(
  go { case begin1(_) =>
    val x1 = 123 // some computation
    val y1 = barrier(x1) // receive value from Process 2 
    val z = further_computation_1(y1)
   },
  go { case begin2(_) =>
    val x2 = 456 // some computation
    val y2 = barrier(x2) // receive value from Process 1
    val z = further_computation_2(y2)
   }
)
begin1() + begin2() // emit both molecules to enable starting the two reactions

```

At this point, the molecule `barrier()` is not yet consumed by any reactions.
We now need to define some reaction that consumes these molecules.

The two processes will each emit one copy of `barrier()`.
It is clear that what we need is a reaction that exchanges the values these two molecules carry.
The easiest solution is to just let these two molecules react with each other.
The reaction will then reply to both of them, exchanging the reply values. 

```scala
go { case barrier(x1, reply1) + barrier(x2, reply2) => reply1(x2) + reply2(x1) }

```

The final code looks like this:

```scala
val begin1 = m[Unit]
val begin2 = m[Unit]

val barrier = b[Int,Int]

site(
  go { case begin1(_) =>
    val x1 = 123 // some computation
    val y1 = barrier(x1) // receive value from Process 2 
    val z = further_computation_1(y1)
   },
  go { case begin2(_) =>
    val x2 = 456 // some computation
    val y2 = barrier(x2) // receive value from Process 1
    val z = further_computation_2(y2)
   },
   go { case barrier(x1, reply1) + barrier(x2, reply2) => reply1(x2) + reply2(x1) }
)
begin1() + begin2() // emit both molecules to enable starting the two reactions

```

Working test code for the rendezvous problem is in `Patterns01Spec.scala`.

## Rendezvous with `n` participants

Suppose we need a rendezvous with `n` participants: there are `n` processes (where `n` is a run-time value, not known in advance),
and each process would like to wait until all other processes reach the rendezvous point.
After that, all `n` waiting processes become unblocked and proceed concurrently.

This is similar to the rendezvous with two participants that we just discussed.
To simplify the problem, we assume that no data is exchanged — this is a pure synchronization task.

Let us try to generalize our previous implementation of the rendezvous from 2 participants to `n`.
Since emitters are values, we could define `n` different emitters `begin1`, ..., `begin_n`, `barrier1`, ..., `barrier_n` and so on.
We could store these emitters in an array and also define an array of corresponding reactions.

However, we notice a problem when we try to generalize the reaction that performs the rendezvous:

```scala
go { case barrier(x1, reply1) + barrier(x2, reply2) => ... }

```

Generalizing this reaction straightforwardly to `n` participants would now require a reaction with `n` input molecules.
However, input molecules to each reaction must be defined statically at compile time.
Since `n` is a run-time parameter, we cannot define a reaction with `n` input molecules.
So we cannot generalize from 2 to `n` participants in this way.

What we need is to consume all `n` blocking molecules `barrier()` and also reply to all of them after we make sure we consumed exactly `n` of them.
The only way to implement chemistry that consumes `n` molecules (where `n` is a run-time parameter) is to consume one molecule at a time while counting to `n`.
We need a molecule, say `counter()`, to carry the integer value that shows the number of `barrier()` molecules already consumed.
Therefore, we need a reaction like this:

```scala
go { case barrier(_, reply) + counter(k) ⇒ 
  ???
  if (k + 1 < n) counter(k + 1); ???; reply()
}

```

This reaction must reply to the `barrier()` molecule, since a reply is required in any reaction that consumes a blocking molecule.
It is clear that `reply()` should come last: this is the reply to the `barrier()` molecule, which should be performed only after we counted up to `n`.
Until then, this reaction must be blocked.

The only way to block in the middle of a reaction is to emit a blocking molecule there.
Therefore, some blocking molecule must be emitted in this reaction before replying to `barrier()`.
Let us make `counter()` a blocking molecule:

```scala
go { case barrier(_, reply) + counter(k, replyCounter) ⇒
  ???
  if (k + 1 < n) counter(k + 1); ???; reply() 
}

```

What is still missing is the reply to the `counter(k)` molecule.
Now we are faced with a question: should we perform that reply before emitting `counter(k + 1)` or after?
Here are the two possible reactions:

```scala
val reaction1 = go { case barrier(_, reply) + counter(k, replyCounter) =>
  replyCounter(); if (k + 1 < n) counter(k + 1); reply()
}
val reaction2 = go { case barrier(_, reply) + counter(k, replyCounter) =>
  if (k + 1 < n) counter(k + 1); replyCounter(); reply()
}

```

We now need to decide whether `reaction1` or `reaction2` works as required.

To resolve this question, let us visualize the molecules that we need to be present at different stages of running the program.
Suppose that `n = 10` and we have already consumed all `barrier()` molecules except three.
We presently have three `barrier()` molecules and one `counter(7)` molecule.
(As we decided, this is a blocking molecule.)

Note that the `counter(7)` molecule was emitted by a previous reaction of the same kind, 

```scala
go { barrier() + counter(6) => ... counter(7); ... }

```

So, this previous reaction is now blocked at the place where it emitted `counter(7)`. 

Next, the reaction `{ barrier() + counter(7) => ... }` will start and consume its input molecules, leaving two `barrier()` molecules present.

Now the reaction body of `{ barrier() + counter(7) => ... }` will run and emit `counter(8)`.
Then we will have the molecules `barrier(), barrier(), counter(8)` in the soup.
This set of molecules will be the same whether we use `reaction1` or `reaction2`.

Suppose we used `reaction1`.
We see that in the body of `reaction1` the reply is sent to `counter(7)` before emitting `counter(8)`.
The reply to `counter(7)` will unblock the previous reaction,
 
```scala
{ case barrier(_, reply) + counter(6, replyCounter) => replyCounter(); counter(7); reply() }

```

which was blocked at the call to `counter(7)`.
Now this reaction can proceed to reply to its `barrier()` using `reply()`.
However, at this point it is too early to send a reply to `barrier()`, because we still have two `barrier()` molecules that we have not yet consumed!

Therefore, `reaction1` is incorrect.
On the other hand, `reaction2` works correctly.
It will block at each emission of `counter(k + 1)` and unblock only when `k = n - 1`.
At that time, `reaction2` will reply to the `counter(n - 1)` molecule and to the `barrier()` molecule just consumed.
The reply to the `counter(n - 1)` molecule will unblock the copy of `reaction2` that emitted it, which will unblock the next molecules.

In this way, the `n`-rendezvous will require all `n` reactions to wait at the "barrier" until all participants reach the barrier, and then unblock all of them.

It remains to see how the very first `barrier()` molecule can be consumed.
We could emit a `counter(0)` molecule at the initial time.
This molecule is blocking, so emitting it will block its emitter until the very end of the `n`-rendezvous.
This may be undesirable.

To avoid that, we can introduce a special initial molecule `counterInit()` with a reaction such as

```scala
go { case barrier(_, reply) + counterInit(_) => counter(1); reply() }

```

This works; the complete code looks like this:

```scala
val barrier = b[Unit,Unit]
val counterInit = m[Unit]
val counter = b[Int,Unit]

site(
  go { case barrier(_, reply) + counterInit(_) =>
    // this reaction will consume the very first barrier molecule emitted
    counter(1) // one reaction has reached the rendezvous point
    reply()
  },
  go { case barrier(_, reply) + counter(k, replyCounter) =>
    if (k + 1 < n) counter(k + 1) // k + 1 reactions have reached the rendezvous point
    replyCounter()
    reply()
  }
)
counterInit() // This needs to be emitted initially.

```

Note that this chemistry will block `n` reactions that emit `barrier()` and another set of `n` copies of `reaction2`.
In the current implementation of `Chymyst`, a blocked reaction always blocks a thread, so the`n`-rendezvous will block `2*n` threads until the rendezvous is passed.
(A future implementation of `Chymyst` might be able to perform a code transformation that never blocks any threads.)

How do we use the `n`-rendezvous?
Suppose we have a reaction where a certain processing step needs to wait for all other reactions to reach the same step.
Then we simply emit the `barrier()` molecule at that step:

```scala
site (
  go { case begin(_) =>
    work()
    barrier() // need to wait here for other reactions
    more_work()
  }
)

(1 to 1000).foreach (begin) // start 1000 copies of this reaction

```

### Refactoring into a function

As always when encapsulating some piece of chemistry into a reusable function, we first figure out what new molecule emitters are necessary for the users of the function.
These new emitters will be returned by the function, while all the other chemistry will remain encapsulated within that function's local scope.

In our case, the users of the function will only need the `barrier` emitter.
In fact, users should _not_ have access to `counter` or `counterInit` emitters:
if users emit any additional copies of these molecules, the encapsulated reactions will begin to work incorrectly.

We also note that the number `n` needs to be given in advance, before creating the reaction site for the `barrier` molecule.
Therefore, we write a function with an integer argument `n`:

```scala
def makeRendezvous(n: Int): B[Unit, Unit] = {
  val barrier = b[Unit,Unit]
  val counterInit = m[Unit]
  val counter = b[Int,Unit]
  
  site(
    go { case barrier(_, reply) + counterInit(_) =>
      // this reaction will consume the very first barrier molecule emitted
      counter(1) // one reaction has reached the rendezvous point
      reply()
    },
    go { case barrier(_, reply) + counter(k, replyCounter) =>
      if (k + 1 < n) counter(k + 1) // k + 1 reactions have reached the rendezvous point
      replyCounter()
      reply()
    }
  )
  counterInit() // This needs to be emitted initially.
  
  barrier // Return only this emitter.
}

```

The usage example will then look like this:

```scala
val barrier = makeRendezvous(1000)

site (
  go { case begin(_) =>
    work()
    barrier() // need to wait here for other reactions
    more_work()
  }
)

(1 to 1000).foreach (begin) // start 1000 copies of this reaction

```


### Reusable `n`-rendezvous

The `n`-rendezvous as implemented in the previous section has a drawback:
Once `n` reactions have passed the rendezvous point, emitting more `barrier()` molecules will have no effect.
If another set of `n` reactions needs to participate in an `n`-rendezvous, a new call to `makeRendezvous()` needs to be made in order to produce a whole new reaction site with a new `barrier()` molecule.

In a _reusable_ `n`-rendezvous,
once a set of `n` participant reactions have passed the rendezvous point, the same `barrier` molecule should automatically become ready to be used again by another set of `n` reactions.
This can be seen as a batching functionality:
If reactions emit a large number of `barrier()` molecules, these molecules are split into batches of size `n`.
A rendezvous procedure is automatically performed for one batch after another. 

Let us see how we can revise the code of `makeRendezvous()` to implement this new requirement.
We need to consider what molecules will be present after one rendezvous is complete.
With our present implementation, the reaction site will have no molecules present because the `counter()` molecule will be consumed at the last iteration, and `counterInit()` was consumed at the very beginning.
At that point, emitting more `barrier()` molecules will therefore not start any reactions.

In order to allow starting the `n`-rendezvous again, we need to make sure that `counterInit()` is again present.
Therefore, the only needed change is to emit `counterInit()` when the `n`-rendezvous is complete:

```scala
if (k + 1 < n) counter(k + 1) else counterInit()

```

After this single change, the `n`-rendezvous function becomes a reusable `n`-rendezvous.

### Exercises

#### Superadmin `n`-rendezvous

Revise the `n`-rendezvous chemistry to allow one "superadmin" reaction, in addition to ordinary reactions.
The "superadmin" reaction can unlock the barrier no matter how many other reactions have reached the barrier so far.
In other words, the "superadmin-`n`-rendezvous" is achieved when either `n` reactions reach the barrier, or the `superadmin` reaction reaches the barrier regardless of how many other reactions have reached so far.
After the superadmin unlocks the barrier, any other reactions can freely pass the barrier.

#### Weighted `n`-rendezvous

Revise the `n`-rendezvous chemistry to allow different "weights" for reactions waiting at the barrier.
Weights are integers.
The `n`-rendezvous is passed when enough reactions reach the barrier so that the sum of all their weights equals `n`.

## Pair up for dance

In the 18th century Paris, there are two doors that open to the dance floor where men must pair up with women to dance.
At random intervals, men and women arrive to the dancing place.
Men queue up at door A, women at door B.

The first man waiting at door A and the first woman waiting at door B will then form a pair and go off to dance.
If a man is first in the queue at door A but no woman is waiting at door B (that is, the other queue is empty), the man needs to wait until a woman arrives and goes off to dance with her.
Similarly, when a woman is first in the queue at door B and no man is waiting, she needs to wait for the next man to arrive, and then go off to dance with him.

(This problem is sometimes called "Leaders and Followers", but this name is misleading since the roles of the two dance partners are completely symmetric.)

Let us implement a simulation of this problem in the chemical machine.

The problem is about controlling the starting of a reaction that represents the "dancing" computation.
The reaction can start when a man and a woman are present.
It is clear that we can simulate this via two molecules, `man` and `woman`, whose presence is required to start the reaction.

```scala
go { case man(_) + woman(_) => beginDancing() }

```

To simplify this example, let us assume that some other reactions will randomly emit `man()` and `woman()` molecules.

The problem with the above reaction is that it does not respect the linear order of molecules in the queue.
If several `man()` and `woman()` molecules are emitted quickly enough, they will be paired up in random order, rather than in the order of arrival in the queue.
Also, nothing prevents several pairs to begin dancing at once, regardless of the dancer's positions in the queues.

How can we enforce the order of arrival on the pairs?

The only way to do that is to label each `man` and `woman` molecule with an integer that represents their position in the queue.
However, the external reactions that emit `man` and `woman` will not know about our ordering requirements.
Therefore, to ensure that the labels are given out consistently, we need our own reactions that will assign position labels.

Let us define new molecules, `manL` representing "man with label" and `womanL` for "woman with label".
The dancing reaction will become something like this,

```scala
val manL = m[Int]
val womanL = m[Int]
go { case manL(m) + womanL(w) if m == w => beginDancing() }

```

The last positions in the men's and women's queues should be maintained and updated as new dancers arrive.
Since the only way of keeping state is by putting data on molecules, we need new molecules that hold the state of the queue.
Let us call these molecules `queueMen` and `queueWomen`.
We can then define reactions that produce `manL` and `womanL` with correct position labels:

```scala
val man = m[Unit]
val manL = m[Int]
val queueMen = m[Int]
val woman = m[Unit]
val womanL = m[Int]
val queueWomen = m[Int]
val beginDancing = m[Unit]

site(
  go { case man(_) + queueMen(n) => manL(n) + queueMen(n+1) },
  go { case woman(_) + queueWomen(n) => womanL(n) + queueWomen(n+1) }
)

```

The result of this chemistry is that a number of `manL` and `womanL` molecules may accumulate at the reaction site, each carrying their position label.
We now need to make sure they start dancing in the order of their position.

For instance, we could have molecules `manL(0)`, `manL(1)`, `manL(2)`, `womanL(0)`, `womanL(1)` at the reaction site.
In that case, we should first let `manL(0)` and `womanL(0)` pair up and begin dancing, and only when they have done so, we may pair up `manL(1)` and `womanL(1)`.

The reaction we thought of,

```scala
go { case manL(m) + womanL(w) if m == w => beginDancing() }

```

does not actually enforce the requirement that `manL(0)` and `womanL(0)` should begin dancing first.
How can we prevent the molecules `manL(1)` and `womanL(1)` from reacting if `manL(0)` and `womanL(0)` have not yet reacted?

In the chemical machine, the only way to prevent reactions is to omit some input molecules.
Therefore, the dancing reaction must have _another_ input molecule, say `mayBegin`.
If the dancing reaction has the form `manL + womanL + mayBegin → ...`, and if `mayBegin` carries value 0,
we can enforce the requirement that `manL(0)` and `womanL(0)` should begin dancing first.

Now it is clear that the `mayBegin` molecule must carry the most recently used position label, and increment this label every time a new pair goes off to dance:

```scala
go { case manL(m) + womanL(w) + mayBegin(l) if m == w && w == l ⇒
  beginDancing(); mayBegin(l + 1)
}

```

In order to make sure that the previous pair has actually began dancing, we can make `beginDancing()` a _blocking_ molecule.
The next `mayBegin` will then be emitted only after `beginDancing` receives a reply, indicating that the dancing process has actually started.

Finally, we must make sure that the auxiliary molecules are emitted only once and with correct values.
We can declare these molecules as static by writing a static reaction:

```scala
go { case _ => queueMen(0) + queueWomen(0) + mayBegin(0) }

```

The complete working code is found in `Patterns01Spec.scala`.

## State machines

A state machine starts out in a certain initial state `s` of type `S` and receives actions of type `A`.
The transition function `tr: (A, S) => S` defines what new state will be chosen when an action `a: A` is received in a given state `s: S`.
While performing the state transition, the state machine can also execute arbitrary code for side effects.

One way of modeling a state machine is to represent the current state by a molecule `state: M[S]` and actions by molecules `action: M[A]`.

```scala
val a = m[A]
val s = m[S]
val tr: (A, S) => S = ???
val initialState: S = ???

site(
  go { case action(a) + state(s) ⇒ sideEffects(a, s); state(tr(a, s)) },
  go { case _ => state(initialState) }
)

```

We declared `state()` as a static molecule because, most likely, it will be necessary to guarantee that there exists only one copy of `state()` in the soup.

If the state type `S` is a disjunction type (in Scala, this is a sealed trait extended by a fixed number of case classes), we may choose another way of modeling the state machine:
Each case class in the disjunction is represented by a different molecule, and there are separate  reactions for transitions involving different states.
Here is an example:

```scala
sealed trait States
case object Initial extends States
final case class State1(x: Int) extends States
final case class State2(s: String) extends States

val stateInit = m[Initial]
val state1 = m[State1]
val state2 = m[State2]

val action = m[A]

site(
  go { case action(a) + stateInit(s) ⇒ ... state1(...) }, // whichever is appropriate
  go { case action(a) + state1(s) ⇒   ... },
  go { case action(a) + state2(s) ⇒   ... }
)

stateInit(Initial) // emit initial state

```

Here, each reaction's body is specialized to the case of the given current state.
This may be a more convenient way of organizing the code.
If the action type `A` is a disjunction type, we can likewise use separate molecules for representing different actions.
Represented in this way, a state machine is translated into declarative code, at the cost of having to define many more molecules and reactions.

## Variations on Readers/Writers

### Ordered `m` : `n` Readers/Writers

The Readers/Writers problem is now reformulated with a new requirement that processes should gain access to the resource in the order they requested it.
The solution must support `m` concurrent Readers and `n` concurrent Writers.

TODO

### Fair `m` : `n` Readers/Writers ("Unisex bathroom")

The key new requirement is that Readers and Writers should be able to work starvation-free.
Even if there is a constant stream of Readers and the ratio is `n` to `1`, a single Writer should not wait indefinitely.
The program should guarantee a fixed upper limit on the waiting time for both Readers and Writers.
However, the order in which Readers and Writers get to work is now unimportant.

### Majority rule `n` : `n` Readers/Writers ("The Modus Hall problem")

For this example, Readers and Writers have equal ratio `n` : `n`.
However, a new rule involving wait times is introduced:
If more Readers than Writers are waiting to access the resource, no more Writers can be granted access, and vice versa.

## Choose and reply to one of many blocking calls (Unix `select`, Actor model's `receive`)

The task is to organize the processing of several blocking calls emitted by different concurrent reactions.
One receiver is available to process one of these calls at a time.

TODO

## Concurrent recursive traversal ("fork/join")

A typical task of "fork/join" type is to traverse recursively a directory that contains many files and subdirectories.
For each file found by the traversal, some operation needs to be performed, such as getting the file size or computing a word count over the file's text.
Finally, all the gathered data needs to be aggregated in some way: for instance, by creating a histogram of file sizes over all files.

This procedure is similar to "map/reduce" in that tasks are first split into smaller sub-tasks and then the results of all sub-tasks are aggregated.
The main difference is that the "fork/join" procedure will split its sub-tasks into further sub-tasks, which may be again be split into yet smaller sub-tasks.
The total number of splitting levels is known only at run time, so the procedure results in a computation tree of more or less arbitrary shape.
The vertices of the tree are sub-tasks that are split; the leaves of the tree are sub-tasks that can be completed without any splitting. 

Tasks of "fork/join" type can be formalized by specifying these four items:

- A type `T` representing a value that fully specifies one sub-task.
- A type `R` representing a partial result value computed by any sub-task (whether or not that subtask was split into further subtasks).
We assume that `R` is a monoid type with a binary operation `++` of type `(R, R) ⇒ R`,
so that we can aggregate partial results into the final result of type `R`.
- A value `init` of type `T` that specifies the main task (the root of the computation tree).
- A function `fork` of type `T ⇒ Either[List[T], R]` that decides whether the task needs to be split.
 If so, `fork` determines the list of new values of type `T` that correspond to the new sub-tasks.
 Otherwise, `fork` will compute a partial result of type `R`. 

We assume for simplicity that the aggregation of partial results can be performed in any order, so that we can use a commutative monoid for `R`.

Let us now implement this generic "fork/join" procedure using `Chymyst`.
The type signature should look like this:

```scala
def doForkJoin[R, T](init: T, fork: T ⇒ Either[List[T], R]): R = ???

```

How would we design the chemistry that performs this procedure?

It is clear that each sub-task needs to run a reaction starting with a molecule that carries a value of type `T`.

```scala
val task = m[T]

site( go { case task(t) ⇒ ??? } )

// Initially, emit one `task` molecule.
task(init)

```

When the reaction `task → ...` is finished, it should either emit a number of other `task` molecules, or return a result value.
Therefore, we need another type of molecule that carries the result value:

```scala
val res = m[R]
val task = m[T]

site(
 go { case task(t) ⇒
  fork(t) match {
       case Left(ts) ⇒ ts.foreach(x ⇒ task(x))
       case Right(r) ⇒ res(r)
  }
 }
)

// Initially, emit one `task` molecule.
task(init)

```

After all `task()` molecules are consumed, a number of `res(...)` molecules will be emitted into the soup.
To aggregate their values into a single final result, we need to add a reaction for `res(...)`:

```scala
go { case res(x) + res(y) ⇒ res(x ++ y) }

```

The result of this chemistry will be that eventually a single `res(r)` molecule will be present in the soup, and its value `r` will be the final result value.
There remains, however, a major problem with the code as written so far: it does not terminate!
The chemistry does not know how many `task` molecules to expect, and the code keeps waiting for more `task(...)` molecules to be emitted into the soup.

Not knowing when molecules are emitted is a fundamental feature of programming in the chemical machine paradigm.
That feature makes the programs robust with respect to accidental slowness of the computer, making race conditions impossible.
The price is the need for additional "bookkeeping" in programs that need to wait until a number of tasks are finished.

In previous chapters, we have already seen two examples of this bookkeeping.
In the "map/reduce" pattern, we put an additional counter on the `result` molecules so that we know when we finished aggregating the partial results.
In the "merge/sort" example, we used a recursive chemical reaction to make sure that the computation tree terminates.

The recursive code is elegant in its way but makes reasoning more complicated.
Let us first try to achieve termination by using a counter.

If `Counter` represents the type of the counter value, we might write code similarly to the "map/reduce" pattern where we add up the counters on `res()` molecules until the total accumulated count reaches a predefined value:

```scala
type Counter = ???
val res = m[(R, Counter)]
val done = m[R]
val total: Counter = ???

go { case res((x, a)) + res((y, b)) ⇒
    val c = a + b
    val r = x ++ y
    if (c < total) res((r, c))
    else done(r)
}

```

However, a simple integer counter will not work in the present situation because we do not know in advance how many `task()` molecules will be generated.
We need a different approach.

Consider an example where the main task is first split into `3` subtasks.
We can imagine that each subtask now has to perform a fraction `1/3` of the total work.
Now suppose that one of these subtasks is further split into `3`, another into `2` subtasks, and the third one is not split.

![Fork/join tree diagram](https://chymyst.github.io/chymyst-core/fork-join-weights.svg)

The computation tree now contains one subtask that needs to perform `1/3` of the work, 2 subtasks that need to perform `1/6` of the work, and 3 subtasks that need to perform `1/9` of the work.
The sum total of the work fractions is `1/3 + 2 * 1/6 + 3 * 1/9 = 1`, as it should be.

Therefore, what we need is to assign _fractional weights_ to the result values of all subtasks.
The sum total of all fractional weights will remain `1`, no matter how we split tasks into subtasks.
When we aggregate partial results, we will add up the fractional weights.
When the weight of a partial result is equal to 1, that result is actually the total final result of the entire computation tree.
In this way we can easily detect the termination of the entire task.

Thus, `Counter` must be a numerical data type that performs exact fractional arithmetic.
For the present computation, we do not actually need a full implementation of fractional arithmetic;
we only need to be able to add two fractions, to divide a fraction by an integer, and to compare fractions with `1`.

Assuming that this data type is available as `SimpleFraction`, we can write the "fork/join" procedure:

```scala
def doForkJoin[R, T](init: T, fork: T ⇒ Either[List[T], R], done: M[R]): Unit = {

  type Counter = SimpleFraction
  
  val res = m[(R, Counter)]
  val task = m[(T, Counter)]
  
  site(
    go { case task((t, c)) ⇒
      fork(t) match {
           case Left(ts) ⇒ ts.foreach(x ⇒ task((x, c / ts.length)))
           case Right(r) ⇒ res((r, c))
      }
     },
    go { case res((x, a)) + res((y, b)) ⇒
        val c = a + b
        val r = x ++ y
        if (c < 1) res((r, c))
        else done(r)
    }
  )
  // Initially, emit one `task` molecule with weight `1`.
  task((init, SimpleFraction(1)))
}

```

### Exercises

#### Bug fixing

The code as shown in the previous section will fail in certain corner cases:

- when any task is split into an empty list of subtasks, the code will divide `c` by `ts.length`, which will be equal to zero
- when there is only one `res()` molecule ever emitted, the reaction `res + res → res` will never run; this will happen, for instance, if the initial task is split into exactly one sub-task, which then immediately returns its result

Fix the chemistry so that the procedure works correctly in these corner cases.

#### Ordered fork/join

In the "fork/join" chemistry just described, partial results are aggregated in an arbitrary order.
Implement the chemistry using recursive reactions instead of counters,
so that the partial results are always aggregated first within the recursive split that generated them.

## Producer-consumer, or `java.util.concurrent.ConcurrentLinkedQueue`

### Unordered bag

There are many producers and consumers working with a single bag of items.
Let us assume for simplicity that an item is identified by a random integer value.

Producers repeatedly add items to the bag, one item at a time.
Consumers repeatedly attempt to fetch items from the bag, one item at a time.
Items can be fetched from the bag in arbitrary order.
If the queue is empty, the fetching operation blocks until another item is added by the producers.

TODO: expand

### Ordered queue

The formulation of the problem is the same as in the unordered version, except
that the bag must be replaced by a FIFO pipeline or queue:
Consumers must receive fetched items in the order these items were added to the queue.

TODO: expand

### Finite unordered bag

The formulation of the problem is the same as in the unordered version, except
that the bag is finite and can hold no more than `n` items.
If the bag already contains `n` items, a call to add another item must block until a consumer withdraws some item from the bag.

### Finite ordered queue

TODO

## Dining philosophers with bounded wait time

Our [simple solution to the "Dining Philosophers" problem](chymyst01.md#example-declarative-solution-for-dining-philosophers) has a flaw:
any given philosopher faces a theoretically unlimited waiting time in the "hungry" state.
For instance, it can happen that philosopher 1 starts eating, which prevents philosopher 2 from eating.
So, fork `f23` remains free and philosopher 3 could start eating concurrently if `f34` is also free.

There is a bound on the maximum time each philosopher will eat.
However, by pure chance, philosophers 1 and 3 could take turns eating for any period of time (1, 3, 1, 3, etc.).
While this is happening, philosopher 2 cannot start eating.
The probability of waiting for any period of time is nonzero.

Let us revise the solution so that we guarantee bounded waiting time for every philosopher.

TODO

## Generalized Smokers/Philosophers/Producers/Consumers problem

There are `n` sorts `A_1`, `A_2`, ..., `A_n` of items that many producers will add at random intervals and in random quantities to the common store. 
There are `n` sorts `P_1`, `P_2`, ..., `P_n` of consumers that look for specific sets of items.
Consumers of sort `P_j` need items of sorts `a_i` for all `i` that satisfy `(j - 1 <= i <= j + 1) mod n`.
Each consumer will at once fetch 3 items from the bag, if possible.

## Finite resource with refill ("Dining savages")

There is a common store of items, an arbitrary number of consumers, and one refilling agent.
The store can hold at most `n` items.
Each consumer will try to call `fetchItem()`, but that function can be called only if the store is not empty.
In that case, `fetchItem()` will remove one item from the store.

Only if the store is empty, the `refill()` function can be called.
However, only one `refill()` call can be made concurrently.

## Finite server queue ("Barber shop problem")

A server can process only one job at a time by calling `processJob()`.
Jobs can be submitted to the server by calling `submit()`.
If the server is busy, the submitted job waits in a queue that can hold up to `n` jobs.
If the queue is full, the submitted job is rejected by calling `reject()`.

Processing each job takes a finite amount of time.
While the job queue is not empty, the server should keep processing jobs.
When the queue is empty, the server goes into an energy-conserving "sleeping" state.
When the queue becomes non-empty while the serves is sleeping, the `wakeUp()` function must be called.
The server should then resume processing the jobs.

### Finite ordered server queue

Same problem, but jobs must be served in the FIFO order.

# Puzzles from "The Little Book of Semaphores"

The formulations of these puzzles are copied verbatim from A. Downey's book.
This copying is permitted by the Creative Commons license.
I will rephrase these problem formulations later, when I get to writing up their solutions.

## Hilzer's "Barber shop"

Our barbershop has three chairs, three barbers, and a waiting area that can accommodate four customers on a sofa and that has standing room for additional customers. Fire codes limit the total number of customers in the shop to 20.

A customer will not enter the shop if it is filled to capacity with other customers. Once inside, the customer takes a seat on the sofa or stands if the sofa is filled. When a barber is free, the customer that has been on the sofa the longest is served and, if there are any standing customers, the one that has been in the shop the longest takes a seat on the sofa. When a customer’s haircut is finished, any barber can accept payment, but because there is only one cash register, payment is accepted for one customer at a time. The barbers divide their time among cutting hair, accepting payment, and sleeping in their chair waiting for a customer.

In other words, the following synchronization constraints apply:

Customers invoke the following functions in order: enterShop, sitOnSofa, getHairCut, pay.

Barbers invoke cutHair and acceptPayment.

Customers cannot invoke enterShop if the shop is at capacity.

If the sofa is full, an arriving customer cannot invoke sitOnSofa.

When a customer invokes getHairCut there should be a corresponding barber executing cutHair concurrently, and vice versa.

It should be possible for up to three customers to execute getHairCut concurrently, and up to three barbers to execute cutHair concurrently.

The customer has to pay before the barber can acceptPayment.

The barber must acceptPayment before the customer can exit.

## The Santa Claus problem

This problem is from William Stallings’s Operating Systems , but he attributes it to John Trono of St. Michael’s College in Vermont.

Santa Claus sleeps in his shop at the North Pole and can only be awakened by either (1) all nine reindeer being back from their vacation in the South Pacific, or (2) some of the elves having difficulty making toys; to allow Santa to get some sleep, the elves can only wake him when three of them have problems. When three elves are having their problems solved, any other elves wishing to visit Santa must wait for those elves to return. If Santa wakes up to find three elves waiting at his shop’s door, along with the last reindeer having come back from the tropics, Santa has decided that the elves can wait until after Christmas, because it is more important to get his sleigh ready. (It is assumed that the reindeer do not want to leave the tropics, and therefore they stay there until the last possible moment.) The last reindeer to arrive must get Santa while the others wait in a warming hut before being harnessed to the sleigh.

Here are some addition specifications:

After the ninth reindeer arrives, Santa must invoke prepareSleigh, and then all nine reindeer must invoke getHitched.

After the third elf arrives, Santa must invoke helpElves. Concurrently, all three elves should invoke getHelp.

All three elves must invoke getHelp before any additional elves enter (increment the elf counter).

Santa should run in a loop so he can help many sets of elves. We can assume that there are exactly 9 reindeer, but there may be any number of elves.

## Building H2O

There are two kinds of threads, oxygen and hydrogen. In order to assemble these threads into water molecules, we have to create a barrier that makes each thread wait until a complete molecule is ready to proceed.

As each thread passes the barrier, it should invoke bond. You must guarantee that all the threads from one molecule invoke bond before any of the threads from the next molecule do.

In other words:

If an oxygen thread arrives at the barrier when no hydrogen threads are present, it has to wait for two hydrogen threads.

If a hydrogen thread arrives at the barrier when no other threads are present, it has to wait for an oxygen thread and another hydrogen thread.

We don’t have to worry about matching the threads up explicitly; that is, the threads do not necessarily know which other threads they are paired up with. The key is just that threads pass the barrier in complete sets; thus, if we examine the sequence of threads that invoke bond and divide them into groups of three, each group should contain one oxygen and two hydrogen threads.

## River crossing problem

This is from a problem set written by Anthony Joseph at U.C. Berkeley, but I don’t know if he is the original author. It is similar to the H2O problem in the sense that it is a peculiar sort of barrier that only allows threads to pass in certain combinations.

Somewhere near Redmond, Washington there is a rowboat that is used by both Linux hackers and Microsoft employees (serfs) to cross a river. The ferry can hold exactly four people; it won’t leave the shore with more or fewer. To guarantee the safety of the passengers, it is not permissible to put one hacker in the boat with three serfs, or to put one serf with three hackers. Any other combination is safe.

As each thread boards the boat it should invoke a function called board. You must guarantee that all four threads from each boatload invoke board before any of the threads from the next boatload do.

After all four threads have invoked board, exactly one of them should call a function named rowBoat, indicating that that thread will take the oars. It doesn’t matter which thread calls the function, as long as one does.

Don’t worry about the direction of travel. Assume we are only interested in traffic going in one of the directions.

## The roller coaster problem

Suppose there are n passenger threads and a car thread. The passengers repeatedly wait to take rides in the car, which can hold C passengers, where C < n. The car can go around the tracks only when it is full.

Here are some additional details:

Passengers should invoke board and unboard.

The car should invoke load, run and unload.

Passengers cannot board until the car has invoked load

The car cannot depart until C passengers have boarded.

Passengers cannot unboard until the car has invoked unload.

Puzzle: Write code for the passengers and car that enforces these constraints.

## Multi-car Roller Coaster problem

This solution does not generalize to the case where there is more than one car. In order to do that, we have to satisfy some additional constraints:

Only one car can be boarding at a time.

Multiple cars can be on the track concurrently.

Since cars can’t pass each other, they have to unload in the same order they boarded.

All the threads from one carload must disembark before any of the threads from subsequent carloads.

Puzzle: modify the previous solution to handle the additional constraints. You can assume that there are m cars, and that each car has a local variable named i that contains an identifier between 0 and m − 1.


## The search-insert-delete problem

Three kinds of threads share access to a singly-linked list: searchers, inserters and deleters. Searchers merely examine the list; hence they can execute concurrently with each other. Inserters add new items to the end of the list; insertions must be mutually exclusive to preclude two inserters from inserting new items at about the same time. However, one insert can proceed in parallel with any number of searches. Finally, deleters remove items from anywhere in the list. At most one deleter process can access the list at a time, and deletion must also be mutually exclusive with searches and insertions.

Puzzle: write code for searchers, inserters and deleters that enforces this kind of three-way categorical mutual exclusion.

## The sushi bar problem
   
This problem was inspired by a problem proposed by Kenneth Reek . Imagine a sushi bar with 5 seats. If you arrive while there is an empty seat, you can take a seat immediately. But if you arrive when all 5 seats are full, that means that all of them are dining together, and you will have to wait for the entire party to leave before you sit down.
   
Puzzle: write code for customers entering and leaving the sushi bar that enforces these requirements.

## The child care problem

At a child care center, state regulations require that there is always one adult present for every three children.
   
Puzzle: Write code for child threads and adult threads that enforces this constraint in a critical section.

Optimize the child care center utilization: children can enter at any time, provided that there are enough adults present. Adults can leave at any time, provided that there are not too many children present.

## The room party problem

The following synchronization constraints apply to students and the Dean of Students:

Any number of students can be in a room at the same time.

The Dean of Students can only enter a room if there are no students in the room (to conduct a search) or if there are more than 50 students in the room (to break up the party).

While the Dean of Students is in the room, no additional students may enter, but students may leave.

The Dean of Students may not leave the room until all students have left.

There is only one Dean of Students, so you do not have to enforce exclusion among multiple deans.

Puzzle: write synchronization code for students and for the Dean of Students that enforces all of these constraints.
   
## The Senate Bus problem

Riders come to a bus stop and wait for a bus. When the bus arrives, all the waiting riders invoke boardBus, but anyone who arrives while the bus is boarding has to wait for the next bus. The capacity of the bus is 50 people; if there are more than 50 people waiting, some will have to wait for the next bus.

When all the waiting riders have boarded, the bus can invoke depart. If the bus arrives when there are no riders, it should depart immediately.

Puzzle: Write synchronization code that enforces all of these constraints.

## The Faneuil Hall problem

“There are three kinds of threads: immigrants, spectators, and a one judge. Immigrants must wait in line, check in, and then sit down. At some point, the judge enters the building. When the judge is in the building, no one may enter, and the immigrants may not leave. Spectators may leave. Once all immigrants check in, the judge can confirm the naturalization. After the confirmation, the immigrants pick up their certificates of U.S. Citizenship. The judge leaves at some point after the confirmation. Spectators may now enter as before. After immigrants get their certificates, they may leave.”

To make these requirements more specific, let’s give the threads some functions to execute, and put constraints on those functions.

Immigrants must invoke enter, checkIn, sitDown, swear, getCertificate and leave.

The judge invokes enter, confirm and leave.

Spectators invoke enter, spectate and leave.

While the judge is in the building, no one may enter and immigrants may not leave.

The judge can not confirm until all immigrants who have invoked enter have also invoked checkIn.

Immigrants can not getCertificate until the judge has executed confirm.

Solve this.

Extended version: modify this solution to handle the additional constraint that after the judge leaves, all immigrants who have been sworn in must leave before the judge can enter again.

## Dining Hall problem

Students in the dining hall invoke dine and then leave. After invoking dine and before invoking leave a student is considered “ready to leave”.

The synchronization constraint that applies to students is that, in order to maintain the illusion of social suave, a student may never sit at a table alone. A student is considered to be sitting alone if everyone else who has invoked dine invokes leave before she has finished dine.

Puzzle: write code that enforces this constraint.

## Extended Dining Hall problem

The Dining Hall problem gets a little more challenging if we add another step. As students come to lunch they invoke getFood, dine and then leave. After invoking getFood and before invoking dine, a student is considered “ready to eat”. Similarly, after invoking dine a student is considered “ready to leave”.

The same synchronization constraint applies: a student may never sit at a table alone. A student is considered to be sitting alone if either

She invokes dine while there is no one else at the table and no one ready to eat, or

everyone else who has invoked dine invokes leave before she has finished dine.

Puzzle: write code that enforces these constraints.

