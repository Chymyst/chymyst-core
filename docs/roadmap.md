<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Version history

- 0.2.1 Implemented new APIs: event reporter traits and per-molecule unit testing hooks. The old APIs `setLogLevel()` and `globalErrorLog` are removed. The `.logSoup` method is renamed to `.logSite`. Bugfix for reactions with repeated molecules and cross-molecule guards. Made it impossible to emit molecules into reaction sites that are defined with errors. First steps towards DCM (distributed chemical machine): `ClusterConfig` and `DM`. Added a book chapter on DCM programming.

- 0.2.0 Renamed `SmartPool` to `BlockingPool` and simplified the thread info handling. More static checks for emission of static molecules. Radical rewrite of blocking molecules code without semaphores and without error replies, simplifying the API. The call `reply.checkTimeout()` is eliminated: the reply emitters now always return `Boolean`. New APIs for reply: `ReplyEmitter.noReplyAttemptedYet` and `B.futureReply`. Reactions will no longer throw exceptions to unblock waiting calls when errors occur. Instead, a more flexible per-reaction error recovery mechanism will be implemented in the future. New functionality for thread pools: pool names and thread names for debugging, shorter pool creation syntax, thread group priority API. Thread pools no longer need to be shut down explicitly (although this might be helpful if there are thousands of them). Added documentation chapter on Chemical Machine as an evolution of the Actor model.

- 0.1.9 Static molecules are now restricted to a linear output context, in a similar way to blocking replies. Reaction schedulers now run on a single dedicated thread; site pools are eliminated. New examples: 8 queens and hierarchical map/reduce. Added a simple facility for deadlock warning, used for `FixedPool`. Tutorial updated with a new "quick start" guide that avoids requiring too many new concepts. Miscellaneous bug fixes and performance improvements.

- 0.1.8 "Singleton" molecules and reactions are now called "static", which is more accurate. Added more tutorial examples, including fork/join and a fully concurrent Game of Life. Some code cleanups and optimizations in the reaction scheduler, especially for reactions with repeated input molecules and cross-molecule conditions. Support for pipelined molecules (an automatic optimization) makes molecule selection faster.

- 0.1.7 New compile-time restrictions, towards guaranteeing single reply for blocking molecules. It is now not allowed to call blocking molecules inside loops, or to emit replies in any non-linear code context (such as, under a closure or in a loop). Change of artifact package from `code.chymyst` to `io.chymyst`. This version is the first one published on Sonatype Maven repository. 

- 0.1.6 A different and type-safe mechanism now implements the syntax `a()` for emitting molecules with `Unit` values; no more auxiliary classes `E`, `BE`, `EB`, `EE`. This simplifies code and eliminates the need for whitebox macros. Breaking API change: `timeout(value)(duration)` instead of `timeout(duration)(value)` used previously. An optimization for the reaction scheduler now makes simple reactions start faster. The project build has been revamped: now there is a single JAR artifact and a single SBT project for `Chymyst`, rather than 3 artifacts as before. A skeleton "hello-world" project is available in a separate repository. `Chymyst` has been moved to a separate repository as well. Various improvements in the compile-time analysis of reactions: livelock detection now understands that molecules emitted under `if/else` constructions are not always emitted.

- 0.1.5 Bug fix for a rare race condition with time-out on blocking molecules. New `checkTimeout` API to make a clean distinction between replies that need to check the timeout status and replies that don't. Documentation was improved. Code cleanups resulted in 100% test coverage. Revamped reaction site code now supports nonlinear input patterns.

- 0.1.4 Simplify API: now users need only one package import. Many more tutorial examples of chemical machine concurrency. Test code coverage is at 97%. More compiler warnings enabled (including deprecation warnings). There are now more intelligent "whitebox" macros that generate different subclasses of `M[T]` and `B[T,R]` when `T` or `R` are the `Unit` type, to avoid deprecation warnings with the syntax `f()`.

- 0.1.3 Major changes in the API ("site", "go" instead of "join", "run") and in the terminology used in the tutorial and in the code: we now use the chemical machine paradigm more consistently, and avoid using the confusing term "join". The build system now uses the `wartremover` SBT plugin, checking for more possible errors. Test code coverage is at 96%.

- 0.1.2 Bug fixes for singletons and for blocking molecules. Documentation revised with help of Philippe Derome. Started to track test code coverage (currently at 95%). New PR builds will not pass if code coverage decreases.

- 0.1.1 Bug fixes for blocking replies; new benchmarks for blocking molecules.

- 0.1.0 First alpha release of `Chymyst`. Changes: implementing static molecules and volatile readers; several important bugfixes.

- 0.0.10 Static checks for livelock and deadlock in reactions, with both compile-time errors and run-time errors.

- 0.0.9 Macros for static analysis of reactions; unrestricted pattern-matching now available for molecule values.

- 0.0.8 Add a timeout option for blocking molecules. Tutorial text and ScalaDocs are almost finished. Minor cleanups and simplifications in the API.

- 0.0.7 Refactor into proper library structure. Add tutorial text and start adding documentation. Minor cleanups. Add `Future`/molecule interface.

- 0.0.6 Initial release on Github. Basic functionality, unit tests.

# Roadmap for the future

These features are considered for implementation in the next versions:

Version 0.1: (Released.) Perform static analysis of reactions, and warn the user about certain situations with unavoidable livelock, deadlock, or indeterminism.

Version 0.2: (Released.) Rewrite the reaction scheduler, optimizing for performance and flexibility.

In particular, do not lock the entire molecule bag - only lock some groups of molecules that have contention on certain molecule inputs (decide this using static analysis information).
(Will not do.)

- start many reactions at once when possible, even at one and the same reaction site (will not do; scheduling has been converted to single-threaded)
- allow nonlinear input patterns and arbitrary guards (done in 0.1.5, optimized in 0.1.8)
- automatic pipelining (i.e. strict ordering of consumed molecules) should give a speedup (done in 0.1.8)
- Use "pipelined" molecules that are optimized for streaming usage (done in 0.1.8).

Version 0.3: 

- Optimize some performance bottlenecks: creating new reactions and new reaction sites is slow
- Investigate interoperability with streaming frameworks such as Scala Streams, Scalaz Streams, FS2, Akka, Akka Streaming, Kafka, etc.

Version 0.4: Enterprise readiness.

- fault tolerance: a new system with per-reaction supervision
- reaction and molecule monitoring, automatic back-pressure, "heating" / "cooling" of reaction sites
- flexible logging (done in 0.2.1)
- assertions on static molecules and perhaps on some other situations
- automatic thread fusion for static or pipelined molecule reactions

Version 0.5: Application framework `Chymyst`
- converting between molecules and various external APIs such as HTTP, GUI toolkits, Unix files and processes
- framework for applications

Version 0.6: Distributed Chemical Machine
- Automatic distributed and fault-tolerant execution of chemical reactions ("site pools" or another mechanism)

Version 0.7: Static optimizations: use advanced macros and code transformations to completely eliminate all blocking and all inessential pattern-matching overhead.

Version 0.8: Distributed execution and cluster deployments.

Version 1.0: Complete enterprise-ready features, adapters to other frameworks, and several real-life projects using `Chymyst Core`. Complete documentation as a separate developer documentation and a book "Concurrency in Reactions: Declarative Scala multiprocessing with `Chymyst`".

# Current To-Do List

 value * difficulty - description

 1 * 1 - add chymyst-examples repo.

 2 * 2 - add a metadata record to molecules. Documentation string for the molecule's value, other metadata. 

 1 * 1 - blocking molecules cannot have reactions with only one input (?) - not sure if this is helpful.

 4 * 5 - do not schedule reactions if queues are full. At the moment, RejectedExecutionException is thrown. It's best to avoid this. Molecules should be accumulated in the bag, to be inspected at a later time (e.g. when some tasks are finished). Insert a call at the end of each reaction, to re-inspect the bag.

 3 * 4 - Blocking molecule emitter's Future[] API should report errors (failure to give a reply value), while a non-Future API doesn't do this. When exception is thrown in the reaction, the Promise will fail. Emitting thread could inspect the promise and detect the failure. In this way, we can still have some error reporting from exceptions (which was removed in 0.2.0). However, a better mechanism could be implemented.
 
 5 * 5 - Implement full error recovery: attach an error handler to a reaction. Error handler is another reaction that has an automatic `Throwable` input and otherwise has the same input molecules as the errored reaction. `go { case a(x) + b(y) => ... } recoverWith { (e: Throwable) => go { case a(x) + b(y) => ... } }`
  Recovery semantics:
  
  - Reaction is _disabled_ for the duration of recovery, i.e. no new instances of the reaction can be scheduled.
  - Recovery handler returns a Policy value which determines whether we:
    - Run the reaction again, keeping its recovery option (this risks an infinite recovery loop)
    - Run the reaction again but disable it permanently if it fails next time (or up to retry count)
    - Disable the reaction permanently and re-emit its consumed inputs back into the soup
    - Disable the reaction permanently but do not re-emit its inputs
 
 1 * 1 - Implement Reaction.withRetry(retry: Boolean) as another alias to the existing API. Also, Reaction.enable(enable: Boolean) ?
 
 5 * 5 - Implement performance metrics either through a given logger or through special molecules.
 Need to monitor: Bag size at reaction site; arrival rate; consumption rate; reaction error rate; reaction compute time; thread utilization (busy / locked waiting / idle) both for scheduler thread and for worker threads.
 
 4 * 4 - Move more code into the `go{}` macro, so that the `Reaction()` constructor has less work to do. For instance: sort the input infos by Scala identifier names, rather than by runtime-assigned molecule names. The sorting can then be done at compile time. Use type signatures for sha1 hashes. Separate Scala code sha1 and runtime-dependent sha1?
 
 5 * 5 - Implement caching of `Reaction` and `ReactionSite` values by sha1 hash, so that we can reuse some data structures if possible instead of recomputing them. (Note that the reactions close over molecule emitters, and reaction sites close over reactions, but many data structures use only molecule indices, and so could be shared rather than recomputed.)
 
 2 * 2 - Detect this condition at the reaction site time:
 A cycle of input molecules being subset of output molecules, possibly spanning several reaction sites (a->b+..., b->c+..., c-> a+...). This is a warning if there are nontrivial matchers and an error otherwise.  - This depends on better detection of output environments.
 
 3 * 3 - define a special "switch off" or "quiescence" molecule - per-RS, with a callback parameter.
 Or, define a "shut down" molecule which will enforce quiescence and/or then shut down the site pool and the reaction pool.

 3 * 3 - add logging of reactions currently in progress at a given RS. (Need a custom thread class, or a registry of reactions?)
 
 3 * 4 - use `java.management` to get statistics over how much time is spent running reactions, waiting while `BlockingIdle()`, etc. 
 This should be a pool-based API and use an external logger; or it can use special molecules.
 
 5 * 5 - reaction sites should detect the situation when another reaction site is pumping molecules into this RS while these molecules can't be consumed quickly enough.
 It should identify which reactions are emitting these molecules, and notify the other RS about it ("backpressure").
 
 3 * 3 Do not schedule reactions (even though input molecules are available) if the RS pool is insufficiently free (e.g. 2x pending tasks for each thread, or more detailed metrics).
 
 2 * 2 - thread pools should have an API for changing the number of threads at run time.

 2 * 2 - interop with Akka actors, in a separate project with its own artifact and dependency. Similarly for interop with Akka Stream, Scalaz Task, Kafka, etc.

 3 * 4 - implement "thread fusion" like in iOS/Android: 1) when a blocking molecule is emitted from a thread T and the corresponding reaction site runs on the same thread T, do not schedule a task but simply run the reaction site synchronously (non-blocking molecules still require a scheduled task? not sure); 2) when a reaction is scheduled from a reaction site that runs on thread T and the reaction is configured to run on the same thread T, do not schedule a task but simply run the reaction synchronously.

 3 * 5 - implement automatic thread fusion for static molecules? — not sure how that would work.
 Can we make at least some reactions, if scheduled very quickly, to be scheduled on the same thread? A good first try is to implement thread fusion for static molecules only. If a static molecule is emitted and another reaction can be scheduled with it, run that other reaction right away, in the reaction closure runner.
 
 4 * 3 - as an option, run a reaction site on the current thread (?) or on a given thread executor (i.e. make a pool out of a given `Executor`, `ExecutionContext`, or `Thread`, is this possible without loss of functionality?).
 
 3 * 3 - is it possible to pre-allocate the reaction closures, rather than creating them each time at runtime?
 
 3 * 3 - simplify code by assuming that static molecules can be only emitted once
 
 2 * 3 - when attaching molecules to futures or futures to molecules, we can perhaps schedule the new futures on the same thread pool as the reaction site to which the molecule is bound? This requires having access to that thread pool. Maybe that access would be handy to users anyway?
 
 5 * 5 - Distributed vs. Remote; molecule vs. reaction vs. reaction site. This yields 6 distinct possibilities for distributed / remote execution. Need to figure out their logical dependencies and implementation possibilities. Note that, compared with the Actor model, we do not need to check that the actor is alive; distributed execution model only needs to verify that (1) network is up, (2) remote application is running.
  
 5 * 5 - implement "progress and safety" assertions so that we could prevent deadlock in more cases
 and be able to better reason about our declarative reactions. First, need to understand what is to be asserted.
 Can we assert non-contention on certain molecules? Can we assert deterministic choice of some reactions? Should we assert the number of certain molecules present (precisely N, or at most N)?

 2 * 4 - allow molecule values to be parameterized types or even higher-kinded types? Need to test this.

 2 * 2 - make memory profiling / benchmarking; how many molecules can we have per 1 GB of RAM? Are reactions or reaction sites being actually garbage-collected if they are not used?

 2 * 2 - add tests for `Pool` such that we submit a closure that sleeps and then submit another closure. Should get / or not get the `RejectedExecutionException`.

 3 * 5 - consider whether we would like to prohibit emitting molecules from non-reaction code. Maybe with a construct such as `withMolecule{ ... }` where the special molecule will be emitted by the system? Can we rewrite tests so that everything happens only inside reactions?

 3 * 3 - implement "one-off" or "perishable" molecules that are emitted once (like static, from the reaction site itself) and may be emitted only if first consumed (but not necessarily emitted at start of the reaction site), and not repeatedly emitted

 5 * 5 - How to rewrite reaction sites so that blocking molecules are transparently replaced by a pair of non-blocking molecules? Can this be done even if blocking emitters are used inside functions? (Perhaps with extra molecules emitted at the end of the function call?) Is it useful to emit an auxiliary molecule at the end of an "if" expression, to avoid code duplication? How can we continue to support real blocking emitters when used outside of macro code? 
 
 2 * 2 - Revisit Philippe's error reporting branch, perhaps salvage some code
  
 3 * 3 - Replace some timed tests by probabilistic tests that run multiple times and fail much less often; perhaps use Li Haoyi's `utest` framework that has features for this.
 
 3 * 3 - `ChymystThread` should keep information about which RS and which reaction is now running. This can be used both for monitoring and for automatic assignment of thread pools for reactions defined in the scope of another reaction. 
 
 3 * 3 - Write a tutorial section about timers and time-outs: cancellable recurring jobs, cancellable subscriptions, time-outs on receiving replies from non-blocking molecules (started on it already)
 
 3 * 3 - Nested reactions could be automatically defined at the same reaction site/thread pool as parent reactions? This seems to be required for the automatic unblocking transformation.
 
 2 * 3 - Error handling should be flexible enough to implement retry at most N times with backoff and other error recovery logic.
 
 3 * 2 - Should we be able to enable and disable reactions at run time? Should we be able to deactivate and reactivate entire reaction sites?
 
 2 * 2 - The new unit-test functionality needs examples; how would I diagnose a deadlock due to off-by-one error in the counter code? How would I use scalacheck to unit-test a reaction? (Emit input molecules with generated values, require output molecule with a value that satisfies a law?)
 
 3 * 5 - implement ING Baker in Chymyst
 
 3 * 3 - figure out whether the dual bipartite graph (e.g. "co-counter") has any usefulness
 
 3 * 5 - implement Benson Ma's example of graph-driven reaction construction. What is a good DSL for describing a DAG or a general graph? Should we use graphs instead of inline chemistry to program Chymyst?
 
 5 * 5 - describe chemistry with DOT graph visualizations https://en.wikipedia.org/wiki/DOT_(graph_description_language)
 
 3 * 4 - formulate the "Async monad" and automatically convert "async" monadic code to Chymyst, with automatic parallelization of applicative subgraphs
 
 To do list for DCM:
 
 - The ClusterBag needs to work same as other bags. All calls are synchronous.
 + The cluster bag or the RS scheduler must know the lock ID and check it before scheduling a reaction and also before starting it. The reaction closure must know the session ID so as to be able to set it in the ChymystThread structure.
 + The lock is removed once molecules are removed and before scheduling a reaction, or it is determined that no reaction can begin.
 - Need to wrap the molecule value as DMolValue[_] after deserializing.
 - Connector must trigger scheduling when watch events occur. Connector must watch all known DRSs.
 - DRS should not schedule reactions if threads are busy. Let molecules accumulate rather than reaction closures, since molecules are cheaper. Possibly, do this with all reactions, not just DRS.
 - Postpone the decision to let static mols be emitted multiple times. This seems to be a good simplification overall, but needs more work.
 - ClusterBag should be able to iterate over *its* molecule values alone. CBag must know the mol index (dm-X). Does it have the necessary info for that?
 
## Will not do for now
 
 3 * 2 - add per-molecule logging; log to file or to logger function (do we need this, if we already have event reporting and test hooks?)

 3 * 4 - LAZY values on molecules? By default? What about pattern-matching then? Probably need to refactor SyncMol and AsyncMol into non-case classes and change some other logic. — Will not do now. Not sure that lazy values on molecules are important as a primitive. We can always simulate them using closures.

 5 * 5 - implement fairness with respect to molecules. - Will not do now. If reactions depend on fairness, something is probably wrong with the chemistry. Instead, pipelining should be a very often occurring optimization.

 3 * 5 - create and use an RDLL (random doubly linked list) data structure for storing molecule values; benchmark. Or use Vector with tail-swapping? This should help fetch random molecules out of the soup. - Will not do now. Not sure what value it brings us if molecule values are truly randomly chosen.

 4 * 5 - implement multiple emission construction a+b+c so that a+b-> and b+c-> reactions are equally likely to start. - Will not do now. Not sure what this accomplishes. The user can randomize the order of emission, if this is crucial for an application.
 
 2 * 2 - Support timers: recurrent or one-off, cancelable molecule emission. — Will not do now. This can be done by user code, unless timers require an explicit thread pool or executor.
 
 3 * 3 - Can we use macros to rewrite f() into f(_) inside reactions for Unit types? Otherwise it seems impossible to implement short syntax `case a() + b() => ` in the input patterns. — No, we can't because { case a() => } doesn't get past the Scala typer, and so macros don't see it at all.
 
 3 * 5 - Can we implement Chymyst Core using Future / Promise and remove all blocking and all semaphores? — No. Automatic concurrent execution of reactions when multiple molecules are available cannot be implemented using promises / futures.

 4 * 5 - allow several reactions to be scheduled *truly simultaneously* out of the same reaction site, when this is possible. Avoid locking the entire bag? - perhaps partition it and lock only some partitions, based on reaction site information gleaned using a macro. — This was attempted, but yields a very complex algorithm and does not give a significant performance boost.

 3 * 3 - perhaps prohibit using explicit thread pools? It's error-prone because the user can forget to stop a pool. Perhaps only expose an API such as `withFixedPool(4){ implicit tp => ...}`? Investigate using implicit values for pools. — doesn't seem to be useful.
 Maybe remove default pools altogether? It seems that every pool needs to be stopped. — However, this would prevent sharing thread pools across scopes. Maybe that is not particularly useful? - Also, with the 0.2.0 changes, pools do not need to be stopped explicitly.

 5 * 5 - Can we perform the unblocking transformation using delimited continuations? — Not sure. Delimited continuations seem to require all code to reside inside `reset()`.
