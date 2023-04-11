ThisBuild / resolvers += "Apache Nexus Snapshots".at("https://repository.apache.org/content/repositories/snapshots/")

import sbt.Keys.parallelExecution
import xerial.sbt.Sonatype.autoImport.sonatypeProfileName

GlobalScope / parallelExecution := false
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

inThisBuild(
  Seq(
    organization := "org.apache.pekko",
    organizationName := "Apache Software Foundation",
    homepage := Some(url("https://pekko.apache.org/")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/apache/incubator-pekko-persistence-r2dbc"),
        "https://github.com/apache/incubator-pekko-persistence-r2dbc.git")),
    startYear := Some(2021),
    developers += Developer(
      "contributors",
      "Contributors",
      "dev@pekko.apache.org",
      url("https://github.com/apache/incubator-pekko-persistence-r2dbc/graphs/contributors")),
    licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),
    description := "An Apache Pekko Persistence backed by SQL database with R2DBC",
    // add snapshot repo when Pekko version overridden
    resolvers ++=
      (if (System.getProperty("override.pekko.version") != null)
         Seq("Apache Nexus Snapshots".at("https://repository.apache.org/content/repositories/snapshots/"))
       else Seq.empty)))

def common: Seq[Setting[_]] =
  Seq(
    crossScalaVersions := Seq(Dependencies.Scala212, Dependencies.Scala213),
    scalaVersion := Dependencies.Scala213,
    crossVersion := CrossVersion.binary,
    sonatypeProfileName := "org.apache.pekko",
    // Setting javac options in common allows IntelliJ IDEA to import them automatically
    Compile / javacOptions ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8"),
    headerLicense := Some(HeaderLicense.Custom("""Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>""")),
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
    projectInfoVersion := (if (isSnapshot.value) "snapshot" else version.value),
    Global / excludeLintKeys += projectInfoVersion)

lazy val dontPublish = Seq(publish / skip := true, Compile / publishArtifact := false)

lazy val root = (project in file("."))
  .settings(common)
  .settings(dontPublish)
  .settings(
    name := "pekko-persistence-r2dbc-root",
    publishTo := Some(Resolver.file("Unused transient repository", file("target/unusedrepo"))))
  .aggregate(core, projection, migration, docs)

def suffixFileFilter(suffix: String): FileFilter = new SimpleFileFilter(f => f.getAbsolutePath.endsWith(suffix))

lazy val core = (project in file("core"))
  .settings(common)
  .settings(name := "pekko-persistence-r2dbc", libraryDependencies ++= Dependencies.core)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .enablePlugins(AutomateHeaderPlugin)

lazy val projection = (project in file("projection"))
  .dependsOn(core)
  .settings(common)
  .settings(name := "pekko-projection-r2dbc", libraryDependencies ++= Dependencies.projection)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .enablePlugins(AutomateHeaderPlugin)

lazy val migration = (project in file("migration"))
  .settings(common)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .settings(
    name := "pekko-persistence-r2dbc-migration",
    libraryDependencies ++= Dependencies.migration,
    Test / mainClass := Some("org.apache.pekko.persistence.r2dbc.migration.MigrationTool"),
    Test / run / fork := true,
    Test / run / javaOptions ++= {
      import scala.collection.JavaConverters._
      // include all passed -Dpekko. properties to the javaOptions for forked tests
      // useful to switch DB dialects for example
      val pekkoProperties = System.getProperties.stringPropertyNames.asScala.toList.collect {
        case key: String if key.startsWith("pekko.") => "-D" + key + "=" + System.getProperty(key)
      }
      "-Dlogback.configurationFile=logback-main.xml" :: "-Xms1G" :: "-Xmx1G" :: "-XX:MaxDirectMemorySize=256M" :: pekkoProperties
    })
  .dependsOn(core % "compile->compile;test->test")
  .enablePlugins(AutomateHeaderPlugin)

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(PekkoParadoxPlugin, ParadoxSitePlugin, PublishRsyncPlugin)
  .dependsOn(core, projection, migration)
  .settings(common)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .settings(dontPublish)
  .settings(
    name := "Apache Pekko Persistence R2DBC",
    libraryDependencies ++= Dependencies.docs,
    previewPath := (Paradox / siteSubdirName).value,
    Paradox / siteSubdirName := s"docs/akka-persistence-r2dbc/${projectInfoVersion.value}",
    pekkoParadoxGithub := Some("https://github.com/apache/incubator-pekko-persistence-r2dbc"),
    paradoxGroups := Map("Language" -> Seq("Java", "Scala")),
    Compile / paradoxProperties ++= Map(
      "project.url" -> "https://doc.akka.io/docs/akka-persistence-r2dbc/current/",
      "canonical.base_url" -> "https://doc.akka.io/docs/akka-persistence-r2dbc/current",
      "pekko.version" -> Dependencies.PekkoVersion,
      "scala.version" -> scalaVersion.value,
      "scala.binary.version" -> scalaBinaryVersion.value,
      "extref.pekko.base_url" -> s"https://doc.akka.io/docs/akka/${Dependencies.PekkoVersionInDocs}/%s",
      "extref.pekko-docs.base_url" -> s"https://pekko.apache.org/docs/pekko/${Dependencies.PekkoVersionInDocs}/%s",
      "extref.pekko-projection.base_url" -> s"https://pekko.apache.org/docs/pekko-projection/${Dependencies.PekkoProjectionVersionInDocs}/%s",
      "extref.java-docs.base_url" -> "https://docs.oracle.com/en/java/javase/11/%s",
      "scaladoc.scala.base_url" -> s"https://www.scala-lang.org/api/current/",
      "scaladoc.org.apache.pekko.base_url" -> s"https://pekko.apache.org/api/pekko/${Dependencies.PekkoVersion}",
      "scaladoc.com.typesafe.config.base_url" -> s"https://lightbend.github.io/config/latest/api/"),
    apidocRootPackage := "org.apache.pekko",
    resolvers += Resolver.jcenterRepo,
    publishRsyncArtifacts += makeSite.value -> "www/",
    publishRsyncHost := "akkarepo@gustav.akka.io")
