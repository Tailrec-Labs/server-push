
name := """server-push"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.github.etaty" %% "rediscala" % "1.8.0",
  "org.asynchttpclient" % "async-http-client" % "2.0.27",
  "com.amazonaws" % "aws-java-sdk-sqs" % "1.11.86",
  "com.amazonaws" % "aws-java-sdk-sns" % "1.11.86",
  "com.amazonaws" % "aws-java-sdk-dynamodb" % "1.11.86",
  "org.apache.activemq" % "activemq-client" % "5.14.3"
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

