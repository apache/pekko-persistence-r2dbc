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

package org.apache.pekko.persistence.r2dbc

import scala.concurrent.Await
import scala.concurrent.duration._
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.persistence.Persistence
import pekko.persistence.r2dbc.internal.R2dbcExecutor
import com.typesafe.config.Config
import io.r2dbc.spi.ConnectionFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite
import org.slf4j.LoggerFactory

trait TestDbLifecycle extends BeforeAndAfterAll { this: Suite =>

  def typedSystem: ActorSystem[_]

  def testConfigPath: String = "pekko.persistence.r2dbc"

  private lazy val config: Config = typedSystem.settings.config

  lazy val journalSettings: JournalSettings = new JournalSettings(config.getConfig(testConfigPath + ".journal"))

  lazy val snapshotSettings: SnapshotSettings = new SnapshotSettings(config.getConfig(testConfigPath + ".snapshot"))

  lazy val stateSettings: StateSettings = new StateSettings(config.getConfig(testConfigPath + ".state"))

  // making sure that test harness does not initialize connection factory for the plugin that is being tested
  lazy val connectionFactoryProvider: ConnectionFactory =
    ConnectionFactoryProvider(typedSystem)
      .connectionFactoryFor("test.connection-factory",
        config.getConfig("pekko.persistence.r2dbc.connection-factory").atPath("test.connection-factory"))

  // this assumes that journal, snapshot store and state use same connection settings
  lazy val r2dbcExecutor: R2dbcExecutor =
    new R2dbcExecutor(
      connectionFactoryProvider,
      LoggerFactory.getLogger(getClass),
      journalSettings.logDbCallsExceeding)(typedSystem.executionContext, typedSystem)

  lazy val persistenceExt: Persistence = Persistence(typedSystem)

  override protected def beforeAll(): Unit = {
    Await.result(
      r2dbcExecutor.updateOne("beforeAll delete")(
        _.createStatement(s"delete from ${journalSettings.journalTableWithSchema}")),
      10.seconds)
    Await.result(
      r2dbcExecutor.updateOne("beforeAll delete")(
        _.createStatement(s"delete from ${snapshotSettings.snapshotsTableWithSchema}")),
      10.seconds)
    Await.result(
      r2dbcExecutor.updateOne("beforeAll delete")(
        _.createStatement(s"delete from ${stateSettings.durableStateTableWithSchema}")),
      10.seconds)
    super.beforeAll()
  }
}
