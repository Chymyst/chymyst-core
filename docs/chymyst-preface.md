<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Preface


`Chymyst` is a library and a framework for declarative concurrent programming.

It follows the **chemical machine** paradigm (known in the academic world as “Join Calculus”) and is implemented as an embedded DSL (domain-specific language) in Scala.

The goal of this tutorial is to explain the chemical machine paradigm and to show examples of implementing concurrent programs in `Chymyst`.
To understand this tutorial, the reader should have some familiarity with the `Scala` programming language.

## Source code

The source code repository for `Chymyst Core` is at [https://github.com/Chymyst/chymyst-core](https://github.com/Chymyst/chymyst-core).

Although this tutorial focuses on using `Chymyst` in the Scala programming language,
one can straightforwardly embed the chemical machine as a library on top of any programming language that has threads and semaphores.
The main concepts and techniques of the chemical machine paradigm are independent of the chosen programming language.
However, a purely functional language is a better fit for the chemical machine.

## Dedication to Robert Boyle (1626-1691)

[![Robert Boyle's self-flowing flask](Boyle_Self-Flowing_Flask.png)](https://en.wikipedia.org/wiki/Robert_Boyle#/media/File:Boyle%27sSelfFlowingFlask.png)

This drawing is by [Robert Boyle](https://en.wikipedia.org/wiki/Robert_Boyle), who was one of the founders of the science of chemistry.
In 1661 he published a treatise titled [_“The Sceptical Chymyst”_](https://upload.wikimedia.org/wikipedia/commons/thumb/d/db/Sceptical_chymist_1661_Boyle_Title_page_AQ18_%283%29.jpg/220px-Sceptical_chymist_1661_Boyle_Title_page_AQ18_%283%29.jpg), from which the `Chymyst` framework borrows its name.
