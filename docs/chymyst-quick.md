<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Quick start

`Chymyst Core` implements a declarative DSL for purely functional concurrency in Scala.

This chapter is for the impatient readers who want to dive straight into the code, with minimal explanations.

## Setup

Take a look at  the ["hello, world" project](https://github.com/Chymyst/helloworld) for a complete minimal example of an application built with `Chymyst Core`.
Here is how it works:

First, declare this library dependency in your `build.sbt`:

```scala
libraryDependencies += "io.chymyst" %% "chymyst-core" % "latest.integration"

```

The `Chymyst Core` DSL becomes available once you import the package `io.chymyst.jc`:

```scala
import io.chymyst.jc._

```

This imports symbols such as `m`, `b`, `site`, `go`, `Reaction` and so on.

## Run an asynchronous computation

Each asynchronous computation is declared as a "reaction" using the `go { }` syntax.
Input and output values for the computation need to be declared separately:

```scala
val in = m[Int] // input value of type `Int`

val result = m[String] // output of type `String`

val reaction1 = go { case in(x) ⇒
  // compute output value
  val z = s"The result is $x"
  println(z) // whatever
  result(z) // emit result value
}

```

## Wait for a result

