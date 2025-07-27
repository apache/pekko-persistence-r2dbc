/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

import net.bzzt.reproduciblebuilds.ReproducibleBuildsPlugin.reproducibleBuildsCheckResolver
import sbt.Keys.parallelExecution

sourceDistName := "apache-pekko-persistence-r2dbc"
sourceDistIncubating := false

ThisBuild / resolvers += Resolver.ApacheMavenSnapshotsRepo
ThisBuild / reproducibleBuildsCheckResolver := Resolver.ApacheMavenStagingRepo

GlobalScope / parallelExecution := false
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

inThisBuild(
  Seq(
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/apache/pekko-persistence-r2dbc"),
        "https://github.com/apache/pekko-persistence-r2dbc.git")),
    startYear := Some(2022),
    developers += Developer(
      "contributors",
      "Contributors",
      "dev@pekko.apache.org",
      url("https://github.com/apache/pekko-persistence-r2dbc/graphs/contributors")),
    description := "An Apache Pekko Persistence implementation backed by SQL database with R2DBC",
    // add snapshot repo when Pekko version overridden
    resolvers ++=
      (if (System.getProperty("override.pekko.version") != null)
         Seq(Resolver.ApacheMavenSnapshotsRepo)
       else Seq.empty)))

lazy val dontPublish = Seq(publish / skip := true, Compile / publishArtifact := false)

lazy val root = (project in file("."))
  .disablePlugins(MimaPlugin)
  .settings(dontPublish)
  .settings(
    name := "pekko-persistence-r2dbc-root")
  .aggregate(core, projection, migration, docs)

def suffixFileFilter(suffix: String): FileFilter = new SimpleFileFilter(f => f.getAbsolutePath.endsWith(suffix))

lazy val core = (project in file("core"))
  .enablePlugins(ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-persistence-r2dbc",
    libraryDependencies ++= Dependencies.core)

lazy val projection = (project in file("projection"))
  .dependsOn(core)
  .enablePlugins(ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-projection-r2dbc",
    libraryDependencies ++= Dependencies.projection,
    dependencyOverrides ++= Dependencies.pekkoTestDependencyOverrides)

lazy val migration = (project in file("migration"))
  .enablePlugins(ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-persistence-r2dbc-migration",
    libraryDependencies ++= Dependencies.migration,
    dependencyOverrides ++= Dependencies.pekkoTestDependencyOverrides,
    Test / mainClass := Some("org.apache.pekko.persistence.r2dbc.migration.MigrationTool"),
    Test / run / fork := true,
    Test / run / javaOptions += "-Dlogback.configurationFile=logback-main.xml",
    mimaPreviousArtifacts := {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((3, _)) =>
          Set.empty
        case _ =>
          mimaPreviousArtifacts.value
      }
    })
  .dependsOn(core % "compile->compile;test->test")

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(PekkoParadoxPlugin, ParadoxSitePlugin, ScalaUnidocPlugin, SitePreviewPlugin)
  .disablePlugins(MimaPlugin)
  .dependsOn(core, projection, migration)
  .settings(dontPublish)
  .settings(
    name := "Apache Pekko Persistence R2DBC",
    libraryDependencies ++= Dependencies.docs,
    previewPath := (Paradox / siteSubdirName).value,
    Paradox / siteSubdirName := s"docs/pekko-persistence-r2dbc/${projectInfoVersion.value}",
    pekkoParadoxGithub := Some("https://github.com/apache/pekko-persistence-r2dbc"),
    paradoxGroups := Map("Language" -> Seq("Java", "Scala")),
    Global / pekkoParadoxIncubatorNotice := None,
    Compile / paradoxProperties ++= Map(
      "project.url" -> "https://pekko.apache.org/docs/pekko-persistence-r2dbc/current/",
      "canonical.base_url" -> "https://pekko.apache.org/docs/pekko-persistence-r2dbc/current",
      "pekko.version" -> Dependencies.PekkoVersion,
      "scala.version" -> scalaVersion.value,
      "scala.binary.version" -> scalaBinaryVersion.value,
      "extref.pekko.base_url" -> s"https://pekko.apache.org/docs/pekko/${Dependencies.PekkoVersionInDocs}/%s",
      "extref.pekko-docs.base_url" -> s"https://pekko.apache.org/docs/pekko/${Dependencies.PekkoVersionInDocs}/%s",
      "extref.pekko-projection.base_url" -> s"https://pekko.apache.org/docs/pekko-projection/${Dependencies.PekkoProjectionVersionInDocs}/%s",
      "extref.java-docs.base_url" -> "https://docs.oracle.com/en/java/javase/11/%s",
      "scaladoc.scala.base_url" -> "https://www.scala-lang.org/api/current/",
      "scaladoc.org.apache.pekko.persistence.r2dbc.base_url" ->
      s"https://pekko.apache.org/api/pekko-persistence-r2dbc/${Dependencies.PekkoPersistenceR2dbcVersionInDocs}",
      "scaladoc.org.apache.pekko.projection.r2dbc.base_url" ->
      s"https://pekko.apache.org/api/pekko-persistence-r2dbc/${Dependencies.PekkoPersistenceR2dbcVersionInDocs}",
      "scaladoc.org.apache.pekko.projection.base_url" ->
      s"https://pekko.apache.org/api/pekko-projection/${Dependencies.PekkoProjectionVersionInDocs}",
      "scaladoc.org.apache.pekko.base_url" -> s"https://pekko.apache.org/api/pekko/${Dependencies.PekkoVersionInDocs}",
      "scaladoc.com.typesafe.config.base_url" -> "https://lightbend.github.io/config/latest/api/"),
    apidocRootPackage := "org.apache.pekko",
    Compile / paradoxMarkdownToHtml / sourceGenerators += Def.taskDyn {
      val targetFile = (Compile / paradox / sourceManaged).value / "license-report.md"

      (LocalRootProject / dumpLicenseReportAggregate).map { dir =>
        IO.copy(List(dir / "pekko-persistence-r2dbc-root-licenses.md" -> targetFile)).toList
      }
    }.taskValue)
