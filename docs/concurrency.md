<link href="{{ site.github.url }}/tables.css" rel="stylesheet">

# The four levels of concurrency

_Written by Sergei Winitzki_

The words "concurrent programming", "asynchronous programming", and "parallel programming" are used in many ways and sometimes interchangeably.
However, there do in fact exist different, inequivalent levels of complexity that we encounter when programming applications that run multiple tasks simultaneously.
I will call these levels "parallel data", "acyclic dataflow", "cyclic dataflow", and "general concurrency".

As we will see, each level is a strict subset of the next.

## Level 1: Parallel data

The main task here is to process a large volume of data more quickly, by splitting the data in smaller subsets and processing all subsets in parallel (on different CPU cores and/or on different machines).

The computation is sequential, and we could have computed the same results on a single processing thread, without any splitting.
Typically, the fully sequential, single-thread computation would be too slow, and thus we are trying to speed it up by using multiple threads.

A typical data-parallel task is to produce a table of word counts in 10,000 different text files.
This class of problems is solved by such frameworks as Scala's parallel collections, Hadoop, and Spark.
The main difficulty for the implementers of these frameworks is to "parallelize" the task, that is, to split the task correctly into subtasks that are as independent as possible,
and to run these subtasks in parallel as efficiently as possible.

### Parallel data = applicative stream

From the type-theoretic point of view, the splitting of the large data set into small chunks is equivalent to replacing the whole data set by a parameterized container type `Stream[T]`,
 where `T` is the type of one chunk of data.
For example, in Spark this parameterized container type is the `RDD[T]` type.

A parallel data framework provides a number of operations on the `Stream[T]` type, such as `map` or `filter`.
These operations make this type into a functor.
Usually, there is also an operation such as `map2` that applies a function of several arguments to several containers at once.
This operation makes `Stream[T]` into an applicative functor.

However, importantly, there is no `flatMap` operation that would make `Stream[T]` into a monad.
A data-parallel framework limits its expressive power to that of an applicative functor.

It is this limitation that makes data-parallel frameworks so effective in their domain of applicability.
As is well known in the functional programming folklore, applicative functor operations are easily and efficiently parallelized, but the monadic `flatMap` operation is not easily parallelized.

## Level 2: Acyclic dataflow

The main task here is to process a large volume of data that is organized as one or more data streams.
Each element of a stream is a chunk of data that can be processed independently from other chunks.
The streams form an acyclic graph that starts "upstream" at "source" vertices and flows "downstream", finally ending at "sink" vertices.
Other vertices of the graph are processing steps that transform the chunks of data in some way.
Usually, programmers want to achieve the maximum throughput (number of chunks consumed at "sources" and delivered to "sinks" per unit time).

The dataflow is _asynchronous_ -- each vertex of the graph can perform its computation and deliver a result to a next vertex, even though that vertex might be still busy with its own processing. 
Since the computations could take different amounts of time at different vertices, certain processing steps will have lower throughput and incur longer wait times on subsequent steps.
To compensate for this, we could run the slower processing steps in parallel on several data chunks at once, while other steps may run sequentially.
Therefore, acyclic dataflow systems can be also called "asynchronous parallel streaming systems".

A typical use case of acyclic dataflow is to implement a high-throughput asynchronous Web server that can start responding to the next request long before the previous request is answered.
This class of problems can be solved by using `Future[T]`, by asynchronous streaming frameworks such as Akka Streaming, scala/async, or FS2, and by various functional reactive programming (FRP) frameworks. 
The main difficulty for the implementers of these frameworks is to interleave the wait times on each thread as much as possible and to avoid wasting CPU cycles when some threads are blocked.

As in the case of data-parallel computations, the acyclic dataflow computation is still a sequential computation, in the following sense:
We could have computed the same results on a single thread and without using any asynchronous computations.
However, this would be too slow, and thus we are trying to speed it up by interleaving the wait times and optimizing thread usage.

### Acyclic dataflow = monadic stream

A stream that carries values of type `T` is naturally represented by a parameterized container type `Stream[T]`.
Arbitrary acyclic dataflow can be implemented only if `Stream[T]` is a monadic functor: in particular, processing a single chunk of type `T` could yield another `Stream[T]` as a result.
Accordingly, most streaming frameworks provide a `flatMap` operation for streams.

A monadic functor is strictly more powerful than an applicative functor, and accordingly the user has more power in implementing the processing pipeline,
in which the next steps can depend in arbitrary ways on other steps.
However, a monadic computation is difficult to parallelize automatically.
Therefore, it is the user who now needs to specify which steps of the pipeline should be parallelized, and which should be separated by an asynchronous "boundary" from other steps.

## Level 3: Cyclic dataflow

The class of problems I call "general dataflow" is very similar to "acyclic dataflow", except for removing the limitation that the dataflow graph should be acyclic.

The main task of general dataflow remains the same - to process data that comes as a stream, chunk after chunk.
However, now we allow any step of the processing pipeline to use a "downstream" step as _input_, thus creating a loop in the dataflow graph.
This loop, of course, must be broken somewhere by an asynchronous boundary: otherwise we will have an actual, synchronous infinite loop in the program.
An "asynchronous infinite loop" means that the output of some downstream processing step will be _later_ fed into the input of some upstream step.  

A typical task that requires a dataflow graph with an asynchronous loop is implementing an event-driven graphical user interface (GUI).
For example, an interactive Excel table with auto-updating cells will have to recompute a number of cells depending on user input events.
However, user input events will depend on what is shown on the screen at a given time; and the contents of the screen depends on data in the cells.
This mutual dependency creates a loop in the dataflow graph:
When the user creates an input event, sending a chunk of data to the computation engine, it updates the table cells on the screen, which allows the user to create further input events that will depend on the new contents of the cells.
The loop in the graph is asynchronous because the user creates new input events _at a later time_ than the cells are updated on the screen. 

This class of problems can be solved by functional reactive programming (FRP) frameworks, Akka Streams, and some other asynchronous streaming systems.
Despite the fact that the general dataflow is strictly more powerful than the acyclic dataflow, the entire computation is _still_ possible to perform synchronously on a single thread without any concurrency.

### Cyclic dataflow = recursive monadic stream

To formalize the difference between general and acyclic dataflow, we note that a loop in the dataflow graph is equivalent to a recursive definition of an asynchronous stream.
In other words, we need the `Stream[T]` type to be a monad with a `monadFix` operation.

For instance, in a typical GUI application implemented in the FRP paradigm, one defines three streams: `Stream[Model]`, `Stream[View]`, and `Stream[Input]`.
The following table illustrates the meaning of these types and the way the three streams depend on each other.

| `Stream[T]` | A value of type `T` represents: | Stream depends on: |
|---|---|---|
|`Stream[Model]` | the data model of the application at a given time| `Stream[Input]` | 
|`Stream[View]` | all windows and UI elements shown on the screen at a given time | `Stream[Model]`  |
|`Stream[Input]` | any of the possible input events that the user might create | `Stream[View]` |

- The model stream depends of the input stream because some input events will update the model.
- The view stream is a function of the model stream because the view shows data from the model.
- The input stream depends on the view stream because the `View` value determines which control elements are visible, and thus determines what input events the user can create while that view is shown on the screen.

Note that the last dependency is _asynchronous_ because the user can create input events only _after_ the view is shown.
Therefore, the three streams are defined mutually recursively, and the stream graph contains an asynchronous loop.

The streaming frameworks that do not support a recursive definition of streams fall into the acyclic dataflow class.

## Level 4: General concurrency

Finally, we consider the general concurrency problems.
The main task in this class of problems is to manage several computations that run concurrently in unknown order and interact in arbitrary ways.
For instance, one computation may at some point stop and wait until another computation computes a certain result, and then examine that result to decide whether to continue its own thread of computation, or wait further, or create new computation threads.

The main difficulty here is to ensure that different processes are synchronized in the desired manner.

Frameworks such as Akka Actors, Go coroutines/channels, and the Java concurrency primitives (`Thread`, `wait/notify`, `synchronized`) are all Level 4 concurrency frameworks.
The chemical machine (known in the academic world as "join calculus") is also a Level 4 framework.

The typical task that requires this level of concurrency is implementing an operating system where many running processes can synchronize and communicate with each other in arbitrary ways.

It seems to me that the "dining philosophers" problem is also an example of a concurrency task that cannot be implemented by any concurrency framework other than a Level 4 framework.
However, I do not know how to prove that this is so.

### Why is Level 4 higher than Level 3

How do we know that Level 4 is truly more powerful than Level 3?

I can give the following argument.
Concurrency at Level 3 (and below) can be simulated on a single thread (although inefficiently).
In other words, any program at Level 3 or below will give the same results when run on multiple threads and on a single thread.

Now we will find an example of a program that cannot be implemented on a single thread.
Consider the following task:

Two processes, A and B, run concurrently and produce result values `x` and `y`.
However, sometimes one of these processes will go into an infinite loop, never producing a result.
It is known that _at most one_ of A and B can go into an infinite loop (but it could be a different process every time).
We need to implement a function `firstResult()` that will run A and B concurrently and wait for the first result that is returned by either of them.
The function needs to return that first result.

Now, we claim that this task cannot be simulated on a single thread, because no program running on a single thread can decide correctly which of the two processes returns first.
Here is why:
Regardless of how we implement `firstResult()`, a single-threaded program will have to run at least one of the processes A and B on that single thread.
Sometimes, that process will go into an infinite loop; in that case, the single thread will be infinitely blocked, and our program will never finish computing `firstResult()`.

So, if implemented on a single thread, `firstResult()` is a _partial function_ (it will sometimes fail to return a value).
However, if we are allowed to use many threads, we can implement `firstResult()` as a _total function_ (it will always return a value),
since now we can run the processes A and B simultaneously on two different threads,
and we are guaranteed that at least one of the processes will return a result.

## Are there other levels?

How can we be sure that there are no other levels of expressive power in concurrency?

Some assurance comes from the mathematical description of these levels:

- We need at least an applicative functor to be able to parallelize computations. This is Level 1.
- Adding `flatMap` to an applicative functor makes it into a monad, and we don't know any intermediate-power functors. Monadic streams is Level 2.
- Adding recursion raises Level 2 to Level 3. There doesn't seem to be anything in between "non-recursive" and "recursive" powers.
- Level 4 supports arbitrary concurrency. In computer science, several concurrency formalisms have been developed (Petri nets, CSP, pi-calculus, Actor model, join calculus), which are all equivalent in power to each other. I do not know of a concurrency language that is strictly less powerful than Level 4 but more powerful than Level 3.


# Which level to use?

It is best to use the level of concurrency that is no higher than what is required to solve the task at hand.

For a data-parallel computation, Spark (Level 1) is the right choice while Akka Streaming (Level 3) is an overkill.

For a streaming pipeline for data processing, Scala standard streams (Level 2) are already adequate, although FS2 or Akka Streaming (Level 3) will offer more flexibility while not over-complicating the user code. Akka Actors will be an overkill, not adding much value to the application, although you will certainly be able to implement a streaming pipeline using raw actors.

For implementing a GUI, a good recursive FRP (Level 3) framework is a must. Raw Akka actors are not necessary but could be used instead of FRP.

If your task is to _implement_ an alternative to Spark (Level 1) or to Scala streams (Level 2), you need a higher-powered framework, and possibly a Level 4 if you need fine-grained control over threads. 
