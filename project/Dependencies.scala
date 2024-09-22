/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

import sbt._

object Dependencies {
  val Scala212 = "2.12.20"
  val Scala213 = "2.13.14"
  val Scala3 = "3.3.3"
  val PekkoVersion = PekkoCoreDependency.version
  val PekkoVersionInDocs = "1.1"
  val PekkoPersistenceJdbcVersion = "1.1.0"
  val PekkoPersistenceR2dbcVersionInDocs = "1.0"
  val PekkoProjectionVersion = "1.1.0-M1"
  val PekkoProjectionVersionInDocs = "1.1"

  object Compile {
    val pekkoActorTyped = "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion
    val pekkoStream = "org.apache.pekko" %% "pekko-stream" % PekkoVersion
    val pekkoPersistence = "org.apache.pekko" %% "pekko-persistence-typed" % PekkoVersion
    val pekkoPersistenceQuery = "org.apache.pekko" %% "pekko-persistence-query" % PekkoVersion

    val pekkoProjectionCore = "org.apache.pekko" %% "pekko-projection-core" % PekkoProjectionVersion

    val r2dbcSpi = "io.r2dbc" % "r2dbc-spi" % "1.0.0.RELEASE"
    val r2dbcPool = "io.r2dbc" % "r2dbc-pool" % "1.0.1.RELEASE"
    val r2dbcPostgres = Seq(
      "org.postgresql" % "r2dbc-postgresql" % "1.0.5.RELEASE")
  }

  object TestDeps {
    val pekkoActor = "org.apache.pekko" %% "pekko-actor" % PekkoVersion % Test
    val pekkoActorTyped = "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion % Test
    val pekkoActorTestkitTyped = "org.apache.pekko" %% "pekko-actor-testkit-typed" % PekkoVersion % Test
    val pekkoJackson = "org.apache.pekko" %% "pekko-serialization-jackson" % PekkoVersion % Test
    val pekkoPersistence = "org.apache.pekko" %% "pekko-persistence" % PekkoVersion % Test
    val pekkoPersistenceQuery = "org.apache.pekko" %% "pekko-persistence-query" % PekkoVersion % Test
    val pekkoPersistenceTyped = "org.apache.pekko" %% "pekko-persistence-typed" % PekkoVersion % Test
    val pekkoPersistenceTck = "org.apache.pekko" %% "pekko-persistence-tck" % PekkoVersion % Test
    val pekkoProtobuf = "org.apache.pekko" %% "pekko-protobuf-v3" % PekkoVersion % Test
    val pekkoSlf4j = "org.apache.pekko" %% "pekko-slf4j" % PekkoVersion % Test
    val pekkoShardingTyped = "org.apache.pekko" %% "pekko-cluster-sharding-typed" % PekkoVersion % Test
    val pekkoStream = "org.apache.pekko" %% "pekko-stream" % PekkoVersion % Test
    val pekkoStreamTestkit = "org.apache.pekko" %% "pekko-stream-testkit" % PekkoVersion % Test
    val pekkoTestkit = "org.apache.pekko" %% "pekko-testkit" % PekkoVersion % Test

    val pekkoProjectionEventSourced =
      "org.apache.pekko" %% "pekko-projection-eventsourced" % PekkoProjectionVersion % Test
    val pekkoProjectionDurableState =
      "org.apache.pekko" %% "pekko-projection-durable-state" % PekkoProjectionVersion % Test
    val pekkoProjectionTestKit = "org.apache.pekko" %% "pekko-projection-testkit" % PekkoProjectionVersion % Test

    val postgresql = "org.postgresql" % "postgresql" % "42.7.4" % Test

    val logback = "ch.qos.logback" % "logback-classic" % "1.3.14" % Test
    val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19" % Test
    val junit = "junit" % "junit" % "4.13.2" % Test
    val junitInterface = "com.novocode" % "junit-interface" % "0.11" % Test
  }

  import Compile._

  val core = Seq(
    pekkoPersistence,
    pekkoPersistenceQuery,
    r2dbcSpi,
    r2dbcPool,
    TestDeps.pekkoPersistenceTck,
    TestDeps.pekkoStreamTestkit,
    TestDeps.pekkoActorTestkitTyped,
    TestDeps.pekkoJackson,
    TestDeps.logback,
    TestDeps.scalaTest) ++ r2dbcPostgres

  val projection = Seq(
    pekkoPersistenceQuery,
    r2dbcSpi,
    r2dbcPool,
    pekkoProjectionCore,
    TestDeps.pekkoProjectionEventSourced,
    TestDeps.pekkoProjectionDurableState,
    TestDeps.pekkoProjectionTestKit,
    TestDeps.pekkoActorTestkitTyped,
    TestDeps.pekkoJackson,
    TestDeps.pekkoStreamTestkit,
    TestDeps.logback,
    TestDeps.scalaTest) ++ r2dbcPostgres

  val migration = Seq(
    "org.apache.pekko" %% "pekko-persistence-jdbc" % PekkoPersistenceJdbcVersion % Test,
    TestDeps.postgresql,
    TestDeps.logback,
    TestDeps.scalaTest)

  val docs = Seq(
    TestDeps.pekkoPersistenceTyped,
    TestDeps.pekkoProjectionEventSourced,
    TestDeps.pekkoProjectionDurableState,
    TestDeps.pekkoShardingTyped)

  val pekkoTestDependencyOverrides = Seq(
    TestDeps.pekkoActor,
    TestDeps.pekkoActorTyped,
    TestDeps.pekkoActorTestkitTyped,
    TestDeps.pekkoJackson,
    TestDeps.pekkoPersistence,
    TestDeps.pekkoPersistenceQuery,
    TestDeps.pekkoProtobuf,
    TestDeps.pekkoSlf4j,
    TestDeps.pekkoStream,
    TestDeps.pekkoStreamTestkit,
    TestDeps.pekkoTestkit)

}
