// This software is distributed under LGPL.

name := "scala-conduit"

version := "0.1"

scalaVersion := "2.10.0"

scalacOptions ++= Seq("-deprecation", "-feature")

//sbt.version := "0.12.2"

//libraryDependencies += "org.scala-tools.testing" %% "scalacheck" % "1.9" % "test"
libraryDependencies += "org.scalatest" % "scalatest_2.10" % "1.9.1" % "test"

// Measure all tests:
//testOptions in Test += Tests.Argument("-oD")
