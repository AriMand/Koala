name := "SprayApp"

version := "1.0"

scalaVersion := "2.11.6"

scalacOptions ++= Seq("-feature", "-deprecation")

resolvers ++= Seq(
  "Sonatype Snapshots"  at "https://oss.sonatype.org/content/repositories/snapshots/",
  "Sonatype Releases"   at "https://oss.sonatype.org/content/repositories/releases/",
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  "spray"               at "http://repo.spray.io/"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka"       %% "akka-actor"             % "2.3.11",
  "com.typesafe.akka"       %% "akka-slf4j"             % "2.3.11",
  "io.spray"                %% "spray-can"              % "1.3.2",
  "io.spray"                %% "spray-routing"          % "1.3.2",
  "io.spray"                %% "spray-json"             % "1.3.2",
  "io.spray"                %% "spray-testkit"          % "1.3.2"     % "test",
  "org.specs2"              %% "specs2"                 % "2.3.13"    % "test"
)

libraryDependencies += "org.apache.kafka" % "kafka_2.11" % "0.9.0.1"

libraryDependencies += "com.typesafe" % "config" % "1.2.1"