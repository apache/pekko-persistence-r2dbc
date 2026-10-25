/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.internal.h2

import java.time.Instant

import scala.concurrent.duration._

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.persistence.SnapshotSelectionCriteria
import pekko.persistence.r2dbc.ConnectionFactoryProvider
import pekko.persistence.r2dbc.JournalSettings
import pekko.persistence.r2dbc.SnapshotSettings
import pekko.persistence.r2dbc.StateSettings
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.internal.InstantFactory
import pekko.persistence.r2dbc.internal.R2dbcExecutor
import pekko.persistence.r2dbc.journal.JournalDao
import pekko.persistence.r2dbc.journal.JournalDao.SerializedJournalRow
import pekko.persistence.r2dbc.snapshot.SnapshotDao
import pekko.persistence.r2dbc.snapshot.SnapshotDao.SerializedSnapshotRow
import pekko.persistence.r2dbc.state.scaladsl.DurableStateDao
import pekko.persistence.r2dbc.state.scaladsl.DurableStateDao.SerializedStateRow
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory

/**
 * The H2 DAOs create the tables of their plugin settings, independent of the dialect that the tests are run with.
 */
class H2TableCreationSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with LogCapturing {

  // plugin settings at the root, using an H2 connection factory with its own in-memory database at `path`
  private def pluginConfig(path: String, pluginPath: String, pluginOverrides: String, h2Overrides: String = ""): Config = {
    val fullConfig = ConfigFactory.load(
      ConfigFactory
        .parseString(s"""
          $path = $${pekko.persistence.r2dbc.connection-factory}
          $path {
            driver = "h2"
            h2 {
              database = "$path"
              $h2Overrides
            }
          }
          """)
        .withFallback(TestConfig.unresolvedConfig))
    ConfigFactory
      .parseString(s"""
        dialect = "h2"
        use-connection-factory = "$path"
        use-app-timestamp = on
        db-timestamp-monotonic-increasing = on
        $pluginOverrides
        """)
      .withFallback(fullConfig.getConfig(pluginPath))
      .withFallback(fullConfig)
  }

  private def executor(path: String, config: Config): R2dbcExecutor =
    new R2dbcExecutor(
      ConnectionFactoryProvider(system).connectionFactoryFor(path, config),
      LoggerFactory.getLogger(getClass),
      logDbCallsExceeding = 1.second,
      closeCallsExceeding = None)(system.executionContext, system)

  private def tables(path: String, config: Config, schema: String): Seq[String] =
    executor(path, config)
      .select("select tables")(
        _.createStatement(
          s"SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '$schema' ORDER BY TABLE_NAME"),
        _.get(0, classOf[String]))
      .futureValue

  private def writeEvent(config: Config): Unit = {
    val dao = JournalDao.fromConfig(JournalSettings(config), config)(system, system.executionContext)
    val now = InstantFactory.now()
    dao
      .writeEvents(
        Seq(SerializedJournalRow(1, "Entity", "Entity|p-1", 1L, now, now, Some(Array[Byte](1)), 1, "", "w", Set.empty,
          None)))
      .futureValue
  }

  private def stateRow(persistenceId: String) =
    SerializedStateRow(persistenceId, 1L, Instant.EPOCH, Instant.EPOCH, Some(Array[Byte](1)), 1, "", Set.empty)

  "The H2 DAOs" should {

    "create the journal table of the plugin" in {
      val path = "h2-tables-journal"
      val config = pluginConfig(path, "pekko.persistence.r2dbc.journal", """table = "other_journal"""")
      writeEvent(config)
      tables(path, config, "PUBLIC") should contain("OTHER_JOURNAL")
    }

    "create the snapshot table of the plugin" in {
      val path = "h2-tables-snapshot"
      val config = pluginConfig(path, "pekko.persistence.r2dbc.snapshot", """table = "other_snapshot"""")
      val dao = SnapshotDao.fromConfig(SnapshotSettings(config), config)(system, system.executionContext)
      dao.store(SerializedSnapshotRow("Entity|p-1", 1L, 0L, Array[Byte](1), 1, "", None)).futureValue
      dao.load("Entity|p-1", SnapshotSelectionCriteria.Latest).futureValue.map(_.seqNr) shouldBe Some(1L)
    }

    "create the durable state table and the custom tables of the plugin" in {
      val path = "h2-tables-state"
      val config = pluginConfig(
        path,
        "pekko.persistence.r2dbc.state",
        """
          table = "other_state"
          custom-table { "CustomEntity" = "custom_state" }
          """)
      val dao = DurableStateDao.fromConfig(StateSettings(config), config)(system, system.executionContext)
      dao.upsertState(stateRow("Entity|p-1"), "s").futureValue
      dao.upsertState(stateRow("CustomEntity|p-1"), "s").futureValue
      tables(path, config, "PUBLIC") should contain allOf ("OTHER_STATE", "CUSTOM_STATE")
    }

    "create the schema of the plugin" in {
      val path = "h2-tables-schema"
      val config = pluginConfig(path, "pekko.persistence.r2dbc.journal", """schema = "other_schema"""")
      writeEvent(config)
      tables(path, config, "OTHER_SCHEMA") shouldBe Seq("EVENT_JOURNAL")
    }

    "run h2-additional-ddl after creating the durable state tables" in {
      val path = "h2-tables-state-ddl"
      val config = pluginConfig(
        path,
        "pekko.persistence.r2dbc.state",
        """h2-additional-ddl = "ALTER TABLE durable_state ADD COLUMN IF NOT EXISTS col1 VARCHAR(256)"""")
      val dao = DurableStateDao.fromConfig(StateSettings(config), config)(system, system.executionContext)
      dao.upsertState(stateRow("Entity|p-1"), "s").futureValue
      executor(path, config)
        .select("select columns")(
          _.createStatement(
            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'DURABLE_STATE' AND COLUMN_NAME = 'COL1'"),
          _.get(0, classOf[String]))
        .futureValue shouldBe Seq("COL1")
    }

    "run additional-init of the connection factory when connecting" in {
      val path = "h2-tables-additional-init"
      val config = pluginConfig(
        path,
        "pekko.persistence.r2dbc.journal",
        "",
        """additional-init = "CREATE TABLE IF NOT EXISTS other_library (id INT)"""")
      tables(path, config, "PUBLIC") should contain("OTHER_LIBRARY")
    }
  }
}
