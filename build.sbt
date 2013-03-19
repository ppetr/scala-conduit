// This software is distributed under LGPL.

name := "scala-conduit"

version := "0.1"

scalaVersion := "2.10.1"

scalacOptions ++= Seq("-deprecation", "-feature")

//sbt.version := "0.12.2"

libraryDependencies += "org.scalatest" % "scalatest_2.10" % "1.9.1" % "test"

// for property checks:
//libraryDependencies += "org.scala-tools.testing" %% "scalacheck" % "1.9" % "test"
libraryDependencies += "org.scalacheck" % "scalacheck_2.10" % "1.10.0" % "test"

// Measure all tests:
//testOptions in Test += Tests.Argument("-oD")
