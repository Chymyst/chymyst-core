<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Version history

- 0.1.8 "Singleton" molecules and reactions are now called "static", which is more accurate. Added more examples, including a fully concurrent Game of Life. Some optimizations in the reaction scheduler.

- 0.1.7 New compile-time restrictions, towards guaranteeing single reply for blocking molecules. It is now not allowed to call blocking molecules inside loops, or to emit replies in any non-linear code context (such as, under a closure or in a loop). Change of artifact package from `code.chymyst` to `io.chymyst`. This version is the first one published on Sonatype Maven repository. 

- 0.1.6 A different mechanism now implements the syntax `a()` for emitting molecules with `Unit` values; no more auxiliary classes `E`, `BE`, `EB`, `EE`, which simplifies code and eliminates the need for whitebox macros. Breaking change: `timeout(value)(duraction)` instead of `timeout(duraction)(value)` as before. An optimization for the reaction scheduler now makes simple reactions start faster. The project build has been revamped: now there is a single JAR artifact and a single SBT project for `Chymyst`, rather than 3 as before. A skeleton "hello-world" project is available in a separate repository. `Chymyst` has been moved to a separate repository as well. Various improvements in the compile-time analysis of reactions: livelock detection now understands that molecules emitted under `if/else` constructions are not always emitted.

- 0.1.5 Bug fix for a rare race condition with time-out on blocking molecules. New `checkTimeout` API to make a clean distinction between replies that need to check the timeout status and replies that don't. Documentation was improved. Code cleanups resulted in 100% test coverage. Revamped reaction site code now supports nonlinear input patterns.

- 0.1.4 Simplify API: now users need only one package import. Many more tutorial examples of chemical machine concurrency. Test code coverage is 97%. More compiler warnings enabled (including deprecation warnings). There are now more intelligent "whitebox" macros that generate different subclasses of `M[T]` and `B[T,R]` when `T` or `R` are the `Unit` type, to avoid deprecation warnings with the syntax `f()`.

- 0.1.3 Major changes in the API ("site", "go" instead of "join", "run") and in the terminology used in the tutorial and in the code: we now use the chemical machine paradigm more consistently, and avoid using the vague term "join". The build system now uses the "wartremover" SBT plugin to check for more possible errors. Test code coverage is at 96%.

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

Version 0.1: (Released.) Perform static analysis of reactions, and warn the user about certain situations with unavoidable livelock, deadlock, or nondeterminism.

Version 0.2: Rewrite the reaction scheduler, optimizing for performance and flexibility.
In particular, do not lock the entire molecule bag - only lock some groups of molecules that have contention on certain molecule inputs (decide this using static analysis information).
This will allow us to implement interesting features such as:

- start many reactions at once when possible, even at one and the same reaction site
- allow nonlinear input patterns and arbitrary guards (done in 0.1.5)
- automatic pipelining (i.e. strict ordering of consumed molecules) should give a speedup

Version 0.3: Investigate interoperability with streaming frameworks such as Scala Streams, Scalaz Streams, FS2, Akka Streaming, Kafka, Heron. Define and use "pipelined" molecules that are optimized for streaming usage.

Version 0.4: Enterprise readiness: fault tolerance, monitoring, flexible logging, assertions on static molecules and perhaps on some other situations, thread fusing for static or pipelined molecule reactions.

Version 0.5: Application framework `Chymyst`, converting between molecules and various external APIs such as HTTP, GUI toolkits, Unix files and processes.

Version 0.6: Automatic distributed and fault-tolerant execution of chemical reactions ("soup pools").

Version 0.7: Static optimizations: use advanced macros and code transformations to completely eliminate all blocking and all inessential pattern-matching overhead.

# Current To-Do List

 value * difficulty - description
  
 2 * 3 - detect livelock due to static molecule emission (at the moment, they are not considered as present inputs?)

 2 * 2 - Detect this condition at the reaction site time:
 A cycle of input molecules being subset of output molecules, possibly spanning several reaction sites (a->b+..., b->c+..., c-> a+...). This is a warning if there are nontrivial matchers and an error otherwise.  - This depends on better detection of output environments.
 
 3 * 3 - define a special "switch off" or "quiescence" molecule - per-join, with a callback parameter.
 Also define a "shut down" molecule which will enforce quiescence and then shut down the site pool and the reaction pool.

 2 * 2 - perhaps use separate molecule bags for molecules with unit value and with non-unit value? for Booleans? for blocking and non-blocking? for constants? for statics / pipelined?

 4 * 5 - allow several reactions to be scheduled *truly simultaneously* out of the same reaction site, when this is possible. Avoid locking the entire bag? - perhaps partition it and lock only some partitions, based on reaction site information gleaned using a macro.

 4 * 5 - do not schedule reactions if queues are full. At the moment, RejectedExecutionException is thrown. It's best to avoid this. Molecules should be accumulated in the bag, to be inspected at a later time (e.g. when some tasks are finished). Insert a call at the end of each reaction, to re-inspect the bag.

 3 * 3 - add logging of reactions currently in progress at a given RS. (Need a custom thread class, or a registry of reactions?)
 
 3 * 4 - use `java.monitoring` to get statistics over how much time is spent running reactions, waiting while BlockingIdle(), etc. 

 2 * 3 - implement `Perishable()` static molecules that can be eventually consumed (but not repeatedly emitted)

 2 * 2 - refactor ActorPool into a separate project with its own artifact and dependency. Similarly for interop with Akka Stream, Scalaz Task etc.

 3 * 4 - implement "thread fusion" like in iOS/Android: 1) when a blocking molecule is emitted from a thread T and the corresponding reaction site runs on the same thread T, do not schedule a task but simply run the reaction site synchronously (non-blocking molecules still require a scheduled task? not sure); 2) when a reaction is scheduled from a reaction site that runs on thread T and the reaction is configured to run on the same thread, do not schedule a task but simply run the reaction synchronously.

 3 * 5 - implement automatic thread fusion for static molecules?
 
 5 * 5 - is it possible to implement distributed execution by sharing the site pool with another machine (but running the reaction sites only on the master node)? Use Paxos, Raft, or other consensus algorithm to ensure consistency?

 3 * 4 - LAZY values on molecules? By default? What about pattern-matching then? Probably need to refactor SyncMol and AsyncMol into non-case classes and change some other logic. — Will not do now. Not sure that lazy values on molecules are important as a primitive. We can always simulate them using closures.

 3 * 5 - Can we implement Chymyst Core using Future / Promise and remove all blocking and all semaphores?

 3 * 2 - add per-molecule logging; log to file or to logger function

 5 * 5 - implement "progress and safety" assertions so that we could prevent deadlock in more cases
 and be able to better reason about our declarative reactions. First, need to understand what is to be asserted.
 Can we assert non-contention on certain molecules? Can we assert deterministic choice of some reactions? Should we assert the number of certain molecules present (precisely N, or at most N)?

 2 * 4 - allow molecule values to be parameterized types or even higher-kinded types? Need to test this.

 2 * 2 - make memory profiling / benchmarking; how many molecules can we have per 1 GB of RAM?

 2 * 2 - annotate thread pools with names. Make a macro for auto-naming thread pools of various kinds.

 2 * 2 - add tests for Pool such that we submit a closure that sleeps and then submit another closure. Should get / or not get the RejectedExecutionException

 3 * 5 - consider whether we would like to prohibit emitting molecules from non-reaction code. Maybe with a construct such as `withMolecule{ ... }` where the special molecule will be emitted by the system? Can we rewrite tests so that everything happens only inside reactions?

 3 * 3 - perhaps prohibit using explicit thread pools? It's error-prone because the user can forget to stop a pool. Perhaps only expose an API such as `withFixedPool(4){ implicit tp => ...}`? Investigate using implicit values for pools.
 Maybe remove default pools altogether? It seems that every pool needs to be stopped.
  
 3 * 3 - implement "one-off" or "perishable" molecules that are emitted once (like static, from the reaction site itself) and may be emitted only if first consumed (but not necessarily emitted)
  
 2 * 2 - If a blocking molecule was emitted without a timeout, we don't need the second semaphore, and checkTimeout() should return `true`
 
 5 * 5 - How to rewrite reaction sites so that blocking molecules are transparently replaced by a pair of non-blocking molecules? Can this be done even if blocking emitters are used inside functions? (Perhaps with extra molecules emitted at the end of the function call?) Is it useful to emit an auxiliary molecule at the end of an "if" expression, to avoid code duplication? How can we continue to support real blocking emitters when used outside of macro code? 
 
 2 * 2 - Revisit Philippe's error reporting branch, perhaps salvage some code
  
 3 * 3 - Replace some timed tests by probabilistic tests that run multiple times and fail much less often; perhaps use Li Haoyi's `utest` framework that has features for this. Also, it has features for specifying compilation errors, which I currently can't do. Investigate adding some minimal sugar to `utest` so that it looks superficially more like `scalatest`.
 
 3 * 3 - Implement "pipelined" molecules, detect them automatically. The criterion is that the union of all conditionals should be factorizable.

 3 * 3 - `SmartThread` should keep information about which RS and which reaction is now running. This can be used both for monitoring and for automatic assignment of thread pools for reactions defined in the scope of another reaction. 
 
## Will not do for now
 
 2 * 3 - investigate using wait/notify instead of semaphore; does it give better performance? - So far, attempts to do this failed.
 
 5 * 5 - implement fairness with respect to molecules. - Will not do now. If reactions depend on fairness, something is probably wrong with the chemistry. Instead, pipelining should be a very often occurring optimization.

 3 * 5 - create and use an RDLL (random doubly linked list) data structure for storing molecule values; benchmark. Or use Vector with tail-swapping? This should help fetch random molecules out of the soup. - Will not do now. Not sure what value it brings us if molecule values are truly randomly chosen.

 4 * 5 - implement multiple emission construction a+b+c so that a+b-> and b+c-> reactions are equally likely to start. - Will not do now. Not sure what this accomplishes. The user can randomize the order of emission, if this is crucial for an application.
 
 2 * 2 - Support timers: recurrent or one-off, cancelable molecule emission. — Will not do now. This can be done by user code, unless timers require an explicit thread pool or executor.
 
 3 * 3 - Can we use macros to rewrite f() into f(_) inside reactions for Unit types? Otherwise it seems impossible to implement short syntax `case a() + b() => ` in the input patterns. — No, we can't because { case a() => } doesn't get past the Scala typer, and so macros don't see it at all.
 
