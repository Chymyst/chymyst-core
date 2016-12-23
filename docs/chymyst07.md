# Patterns of concurrency

To get more familiar with programming the chemical machine, let us now implement a number of simple concurrent programs.
These programs are 

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

The chemical model is that a blocking molecule `wait` reacts with another, non-blocking molecule `godot`; but `godot` never appears in the soup.

We also need to make sure that the molecule `godot()` is never emitted into the soup.
So we declare `godot` locally within the scope of `wait_forever`, where we'll emit nothing into the soup.

```scala
def wait_forever: B[Unit, Unit] = {
  val godot = m[Unit]
  val waiting_for = b[Unit, Unit]

  site( go { case  waiting_for(_, r) + godot(_) => r() } )
  // forgot to emit `godot` here, which is key to starve this reaction.
  waiting_for
}

```

The function `wait_forever` will return a blocking molecule emitter that, when called, will block forever, never returning any value.

## Rendezvous

