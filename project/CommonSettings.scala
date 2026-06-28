/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

import com.lightbend.paradox.projectinfo.ParadoxProjectInfoPlugin.autoImport.projectInfoVersion
import org.mdedetrich.apache.sonatype.ApacheSonatypePlugin
import sbt.Keys._
import sbt.{ AutoPlugin, Compile, CrossVersion, Global, Test, TestFrameworks, Tests }
import sbt.plugins.JvmPlugin
import sbtdynver.DynVerPlugin
import sbtdynver.DynVerPlugin.autoImport.dynverSonatypeSnapshots

object CommonSettings extends AutoPlugin {
  override def trigger = allRequirements

  override def requires = JvmPlugin && ApacheSonatypePlugin && DynVerPlugin

  override lazy val projectSettings = Seq(
    crossScalaVersions := Seq(Dependencies.Scala213, Dependencies.Scala3),
    scalaVersion := Dependencies.Scala213,
    crossVersion := CrossVersion.binary,
    // Setting javac options in common allows IntelliJ IDEA to import them automatically
    Compile / javacOptions ++= Seq("-encoding", "UTF-8", "--release", "17"),
    scalacOptions ++= Seq("-deprecation", "-feature", "-Xfatal-warnings"),
    scalacOptions ++= {
      val commonWconf = Seq(
        // r2dbc-spi annotation parsing warning - external dependency, cannot be fixed
        "-Wconf:msg=could not find MAYBE in enum:s",
        // existential type feature warning in ConnectionFactoryProvider - wildcard type required
        "-language:existentials")
      if (scalaBinaryVersion.value == "3")
        commonWconf ++ Seq(
          "-Werror",
          "-release:17",
          "-Wconf:msg=Implicit parameters should be provided with a `using` clause:s",
          "-Wconf:msg=is deprecated for wildcard arguments of types:s",
          "-Wconf:msg=The trailing ` _` for eta-expansion is unnecessary:s",
          "-Wconf:msg=with as a type operator has been deprecated:s",
          "-Wconf:msg=Unreachable case except for null:s",
          "-Wconf:msg=is no longer supported for vararg splices:s",
          "-Wconf:msg=bad option.*-Xfatal-warnings:s") ++
        (if (CrossVersion.partialVersion(scalaVersion.value).exists(_._2 < 9))
           Seq("-Yfuture-lazy-vals", "-Wconf:msg=bad option.*-Yfuture-lazy-vals:s")
         else Seq.empty)
      else commonWconf
    },
    Compile / doc / scalacOptions --= Seq("-Xfatal-warnings"),
    Test / logBuffered := false,
    Test / parallelExecution := false,
    // show full stack traces and test case durations
    Test / testOptions += Tests.Argument("-oDF"),
    // -v Log "test run started" / "test started" / "test run finished" events on log level "info" instead of "debug".
    // -a Show stack traces and exception class name for AssertionErrors.
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
    Test / fork := true, // some non-heap memory is leaking
    Test / javaOptions ++= {
      import scala.collection.JavaConverters._
      // include all passed -Dpekko. properties to the javaOptions for forked tests
      // useful to switch DB dialects for example
      val pekkoProperties = System.getProperties.stringPropertyNames.asScala.toList.collect {
        case key: String if key.startsWith("pekko.") => "-D" + key + "=" + System.getProperty(key)
      }
      "-Xms1G" :: "-Xmx1G" :: "-XX:MaxDirectMemorySize=256M" :: pekkoProperties
    },
    projectInfoVersion := (if (isSnapshot.value) "snapshot" else version.value))

  override lazy val globalSettings = Seq(
    Global / excludeLintKeys += projectInfoVersion)

  override lazy val buildSettings = Seq(
    dynverSonatypeSnapshots := true)
}
