// Following instructions from https://github.com/xerial/sbt-sonatype
// see https://issues.sonatype.org/browse/OSSRH-27720
pomExtra in Global :=
    <url>https://chymyst.github.io/joinrun-scala/</url>
    <licenses>
      <license>
        <name>Apache License, Version 2.0</name>
        <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
        <distribution>repo</distribution>
        <comments>A business-friendly OSS license</comments>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:Chymyst/joinrun-scala.git</url>
      <connection>scm:git:git@github.com:Chymyst/joinrun-scala.git</connection>
    </scm>
    <developers>
      <developer>
        <id>winitzki</id>
        <name>Sergei Winitzki</name>
        <url>https://sites.google.com/site/winitzki</url>
      </developer>
      <developer>
        <id>phderome</id>
        <name>Philippe Derome</name>
        <url>https://ca.linkedin.com/in/philderome</url>
      </developer>
    </developers>

sonatypeProfileName := "winitzki"
