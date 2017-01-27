# Choosing the concurrency stack

_by Sergei Winitzki_

### Published on January 27, 2017 on [LinkedIn](https://www.linkedin.com/pulse/choosing-concurrency-stack-sergei-winitzki)

It has become a truism that modern software needs to be concurrent. The software engineering pundits [kept telling us](http://www.technologyreview.com/s/601441/moores-law-is-dead-now-what/) for the [last 20 years](http://www.technologyreview.com/s/400710/the-end-of-moores-law/) that concurrency is the only way to process the ever-increasing data volumes, because computer chips [will not get any faster](http://www.marctomarket.com/2014/01/great-graphic-is-this-end-of-moores-law.html).

The pundits didn't tell us _how_ we are to write concurrent software. As most software engineers know, writing concurrent programs is [notoriously difficult](http://blog.smartbear.com/programming/why-johnny-cant-write-multithreaded-programs/). When several computations run on different threads at once, the behavior of the program often depends sensitively on the timings of the individual computations. For this reason, a concurrent program runs slightly differently _every time_ it is run, and developers can never be sure to have done "enough" debugging and testing. Books such as "[Concurrent Programming in Java](http://www.amazon.com/Concurrent-Programming-Java%C2%99-Principles-Pattern/dp/0201310090)" describe many tricks for managing and synchronizing threads, to help developers avoid the dreaded quagmire of bugs and race conditions that they have come to expect from multithreaded programming.

Early on, Google realized the need to tackle this problem. Their solution was to develop the "[map/reduce](http://research.google.com/archive/mapreduce.html)" paradigm -- a much more [declarative](http://en.wikipedia.org/wiki/Declarative_programming) (although limited) approach to concurrent computation. It is perhaps fair to say that Google could not have succeeded in processing their humongous data sets without "map/reduce". If Google programmed their massively concurrent processing pipelines the old way, -- with Java threads and semaphores, -- there wouldn't have been enough engineer-hours to debug all the race conditions.

Google was followed by other Big Data companies such as Netflix and Twitter. These companies needed to develop tools for different kinds of large-volume data processing. As a result, software engineers today have at their disposal a slew of specialized concurrency frameworks: [Hadoop](http://hadoop.apache.org/), [Spark](http://spark.apache.org/), [Flink](http://flink.apache.org/), [Kafka](http://kafka.apache.org/), [Heron](http://twitter.github.io/heron/), [Akka](http://www.lightbend.com/platform/development/akka), just to name a few. Modern programming languages also offer high-level concurrency facilities such as [parallel collections](http://docs.scala-lang.org/overviews/parallel-collections/overview.html), [Futures / Promises](http://en.wikipedia.org/wiki/Futures_and_promises), and [Async / Await](http://msdn.microsoft.com/en-us/library/mt674882.aspx). Still other options are to use the [Erlang](http://www.erlang.org/) language that implements the [Actor Model](http://en.wikipedia.org/wiki/Actor_model), or Google's [Go language](http://golang.org/) that incorporates [coroutines](http://en.wikipedia.org/wiki/Coroutine) and [CSP channels](http://en.wikipedia.org/wiki/Communicating_sequential_processes) as basic concurrency primitives. Software architects today need to make an informed choice of the concurrency stack suitable for each particular application.

One way of making sense of so many seemingly unrelated concurrency concepts is to ask what classes of problems are being solved. I think there are _four_ distinct levels of complexity in concurrent programming. I call these levels “parallel data”, “acyclic streaming”, “cyclic streaming”, and “general concurrency”. As we will see, each level is a strict subset of the next. Let me explain each of them in turn.

## Level 1: Parallel data

A typical data-parallel task is to produce a table of word counts in 10,000 different text files. Each text file can be processed independently of all others, which is naturally parallelized.

This class of problems is solved by "map/reduce"-like technologies such as [Scala’s parallel collections](http://docs.scala-lang.org/overviews/parallel-collections/overview.html), Hadoop, and Spark. The programmer manipulates the parallel data using operations such as "map", "reduce", and "filter", as if the entire data were a single array. The framework will transparently split the computation between different CPUs and/or different computers on a cluster.

## Level 2: Acyclic streaming

The main task here is to process a large volume of data that is organized as one or more data streams. Each element of a stream is a small chunk of data that can be processed independently from other chunks.

In the acyclic streaming pipeline, the processing of one chunk can depend in an arbitrary way on the results of processing _previous_ chunks. Because of this dependency, the "map/reduce" paradigm is unable to solve this class of problems.

The data stream can be visualized as an acyclic graph that starts upstream at "source" nodes and flows downstream, finally ending at "sink" nodes. Intermediate nodes of the graph represent processing steps that transform the chunks of data in some way. No loops in the graph are allowed -- the data flows strictly in the downstream direction, although streams can fork and join.

The streaming architecture gives data engineers a lot of flexibility in scaling and optimizing the performance of the pipeline. To achieve the maximum throughput, programmers can monitor the data flow and find the processing nodes that present a performance bottleneck. At any step, the data flow can be made _asynchronous_: one step can perform its computation and deliver a result to the next step, even though that step might be still busy with its own processing. As another performance optimization, the slower processing steps could be configured to run in parallel on several data chunks at once, while other steps still run sequentially on each chunk.

A typical use case of acyclic streaming is to implement a high-throughput asynchronous Web server. When implemented with asynchronous processing steps, the server can start responding to the next request long before the previous request is answered.

Streaming frameworks include promises / futures, async / await, Akka Streaming, Flink, and the various [functional reactive programming](http://en.wikipedia.org/wiki/Functional_reactive_programming) (FRP) frameworks such as [reactivex.io](http://reactivex.io/).

## Level 3: Cyclic streaming

This class of problems is very similar to acyclic streaming, except for removing the limitation that the data flow graph be acyclic.

The main task of general streaming remains the same -- to process data that comes as a stream, chunk after chunk. However, now we allow any step of the processing pipeline to be used as input by a later upstream step. This creates an asynchronous loop in the data flow graph.

A typical program that requires cyclic streaming is an event-driven graphical user interface (GUI), when implemented in the FRP paradigm. Consider, for example, an interactive Excel table with auto-updating cells. The program will have to recompute a number of cells depending on user input events. User input events will depend on what is shown on the screen previously; and the contents of the screen depends on data in the cells. This mutually recursive dependency creates a loop in the data flow graph. The loop in the graph is asynchronous because the user creates new input events _later_ than cells are updated on the screen.

This class of problems can be solved by functional reactive programming (FRP) frameworks such as the [Elm language](http://elm-lang.org/), Haskell's [Reflex library](http://github.com/reflex-frp/reflex), Akka Streaming, and some other asynchronous streaming systems.

Cyclic streaming is usually not necessary for pure data processing pipelines because they do not involve interacting with users or other processes in real time, and so there are no asynchronous loops in the data flow.

## Level 4: General concurrency

Finally, consider the most general concurrency problem: to manage many computation threads running concurrently in unknown order and interacting in arbitrary ways. For instance, one thread may start another, then stop and wait until the other thread computes a certain result, and then examine that result to decide whether to continue its own computation, to wait further, or to create new computation threads.

The main difficulty here is to ensure that different threads are synchronized in the desired manner.

Frameworks such as Akka Actors, Go coroutines/channels, and the Java concurrency primitives (Thread, wait/notify, synchronized) are all Level 4 concurrency systems. Recently I have published a new open-source implementation of the [abstract chemical machine](http://chymyst.github.io/joinrun-scala/) (known in the academic world as “[Join Calculus](http://wiki.c2.com/?JoinCalculus)”), which is also a Level 4 concurrency system.

A typical program that requires this level of concurrency is an operating system where many running processes can synchronize and communicate with each other in arbitrary ways. Processes can also monitor and start or terminate other processes. This is the crucial piece of functionality that no streaming framework can provide.

## Which concurrency stack to use?

As I have shown, each concurrency level is a strict subset of the next one in terms of expressive power. Clearly, the programmer should use the level of concurrency no higher than what is required to solve the task at hand. Experience shows that when unnecessarily higher-power features are available, developers _will_ use them and the code _will_ become difficult to manage. It is best to limit the possibilities up front, once it is established that a certain concurrency framework is adequate for the task.

For a batch data processing pipeline, data-parallel frameworks such as Spark (Level 1) are the right choice -- while Akka Streaming (Level 3) is an overkill.

For realtime data processing, a Level 2 streaming framework is usually adequate. The simplest prototype solution can use plain _Future_'s. Akka Streaming (Level 3) will offer more deployment flexibility while not significantly over-complicating the user code. However, using Akka Actors (Level 4) or Go channels (Level 4) is an overkill for that application. It is certainly possible to implement a streaming pipeline using raw actors -- that's what Akka Streaming does under the hood -- but the program will become unnecessarily complicated, and the maintenance of the code base will become difficult.

For implementing an event-driven concurrent GUI, a good FRP (Level 3) framework is a must. Raw Akka actors (Level 4) are not necessary for that, and while they could be used instead of FRP, it is not clear that the advantage of a more visual program design will offset the risk of using Level 4 complexity.

If your task is to _implement an alternative_ to Spark (Level 1) or to Scala streams (Level 2), you need a higher-powered framework, and possibly a Level 4 if you need fine-grained control over threads. Implementing an operating system certainly requires Level 4.

## Conclusion

When choosing the concurrency framework for a given task, the first step for a software architect is to classify the complexity of the task according to the four levels I outlined. This classification is broad and glosses over features of particular frameworks that could be a deal breaker. (Does Spark support file encryption? Does Kafka handle dynamic network configurations?) However, these issues need to be considered _after_ determining the complexity level of the task at hand.

Concurrency stacks compete with each other and often add features that go beyond their intended complexity levels. For instance, a Level 2 framework may add certain process monitoring features that, strictly speaking, belong to Level 4. Developers need to be aware of this and exercise discipline to avoid the "paradigm leakage". If Level 2 is sufficient for implementing our application, we should not use features that belong to Level 3 or Level 4 paradigms, even if our chosen framework provides them.

Choosing the right concurrency stack _and_ following the corresponding paradigm is the key to making concurrency practical.
