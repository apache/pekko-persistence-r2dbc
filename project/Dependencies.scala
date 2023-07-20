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
  val Scala212 = "2.12.18"
  val Scala213 = "2.13.11"
  val PekkoVersion = System.getProperty("override.pekko.version", "0.0.0+26656-898c6970-SNAPSHOT")
  val PekkoVersionInDocs = "current"
  val PekkoProjectionVersion = "0.0.0+32-a55714ec-SNAPSHOT"
  val PekkoProjectionVersionInDocs = "current"

  object Compile {
    val pekkoActorTyped = "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion
    val pekkoStream = "org.apache.pekko" %% "pekko-stream" % PekkoVersion
    val pekkoPersistence = "org.apache.pekko" %% "pekko-persistence-typed" % PekkoVersion
    val pekkoPersistenceQuery = "org.apache.pekko" %% "pekko-persistence-query" % PekkoVersion

    val pekkoProjectionCore = "org.apache.pekko" %% "pekko-projection-core" % PekkoProjectionVersion

    val r2dbcSpi = "io.r2dbc" % "r2dbc-spi" % "0.9.1.RELEASE"
    val r2dbcPool = "io.r2dbc" % "r2dbc-pool" % "0.9.1.RELEASE"
    val r2dbcPostgres = "org.postgresql" % "r2dbc-postgresql" % "0.9.1.RELEASE"
  }

  object TestDeps {
    val pekkoPersistenceTyped = "org.apache.pekko" %% "pekko-persistence-typed" % PekkoVersion % Test
    val pekkoShardingTyped = "org.apache.pekko" %% "pekko-cluster-sharding-typed" % PekkoVersion % Test
    val pekkoPersistenceTck = "org.apache.pekko" %% "pekko-persistence-tck" % PekkoVersion % Test
    val pekkoTestkit = "org.apache.pekko" %% "pekko-actor-testkit-typed" % PekkoVersion % Test
    val pekkoStreamTestkit = "org.apache.pekko" %% "pekko-stream-testkit" % PekkoVersion % Test
    val pekkoJackson = "org.apache.pekko" %% "pekko-serialization-jackson" % PekkoVersion % Test

    val pekkoProjectionEventSourced =
      "org.apache.pekko" %% "pekko-projection-eventsourced" % PekkoProjectionVersion % Test
    val pekkoProjectionDurableState =
      "org.apache.pekko" %% "pekko-projection-durable-state" % PekkoProjectionVersion % Test
    val pekkoProjectionTestKit = "org.apache.pekko" %% "pekko-projection-testkit" % PekkoProjectionVersion % Test

    val postgresql = "org.postgresql" % "postgresql" % "42.3.4" % Test

    val logback = "ch.qos.logback" % "logback-classic" % "1.2.11" % Test // EPL 1.0 / LGPL 2.1
    val scalaTest = "org.scalatest" %% "scalatest" % "3.2.14" % Test // ApacheV2
    val junit = "junit" % "junit" % "4.12" % Test // Eclipse Public License 1.0
    val junitInterface = "com.novocode" % "junit-interface" % "0.11" % Test // "BSD 2-Clause"
  }

  import Compile._

  val core = Seq(
    pekkoPersistence,
    pekkoPersistenceQuery,
    r2dbcSpi,
    r2dbcPool,
    r2dbcPostgres,
    TestDeps.pekkoPersistenceTck,
    TestDeps.pekkoStreamTestkit,
    TestDeps.pekkoTestkit,
    TestDeps.pekkoJackson,
    TestDeps.logback,
    TestDeps.scalaTest)

  val projection = Seq(
    pekkoPersistenceQuery,
    r2dbcSpi,
    r2dbcPool,
    r2dbcPostgres,
    pekkoProjectionCore,
    TestDeps.pekkoProjectionEventSourced,
    TestDeps.pekkoProjectionDurableState,
    TestDeps.pekkoStreamTestkit,
    TestDeps.pekkoTestkit,
    TestDeps.pekkoProjectionTestKit,
    TestDeps.pekkoJackson,
    TestDeps.logback,
    TestDeps.scalaTest)

  val migration =
    Seq(
      "org.apache.pekko" %% "pekko-persistence-jdbc" % "0.0.0+966-e6f717eb-SNAPSHOT" % Test,
      TestDeps.postgresql,
      TestDeps.logback,
      TestDeps.scalaTest)

  val docs =
    Seq(
      TestDeps.pekkoPersistenceTyped,
      TestDeps.pekkoProjectionEventSourced,
      TestDeps.pekkoProjectionDurableState,
      TestDeps.pekkoShardingTyped)
}
