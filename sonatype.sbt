// Following instructions from https://github.com/xerial/sbt-sonatype
// see https://issues.sonatype.org/browse/OSSRH-27720
pomExtra in Global :=
    <inceptionYear>2016</inceptionYear>
    <scm>
      <url>git@github.com:Chymyst/chymyst-core.git</url>
      <connection>scm:git:git@github.com:Chymyst/chymyst-core.git</connection>
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
