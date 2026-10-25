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
  private def pluginConfig(path: String, pluginPath: String, pluginOverrides: String): Config = {
    val fullConfig = ConfigFactory.load(
      ConfigFactory
        .parseString(s"""
          $path = $${pekko.persistence.r2dbc.connection-factory}
          $path {
            driver = "h2"
            h2.database = "$path"
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
      (tables(path, config, "PUBLIC") should contain).allOf("OTHER_STATE", "CUSTOM_STATE")
    }

    "create the schema of the plugin" in {
      val path = "h2-tables-schema"
      val config = pluginConfig(path, "pekko.persistence.r2dbc.journal", """schema = "other_schema"""")
      writeEvent(config)
      tables(path, config, "OTHER_SCHEMA") shouldBe Seq("EVENT_JOURNAL")
    }

    "run h2.additional-init of the journal plugin after creating its table" in {
      val path = "h2-tables-journal-init"
      val config = pluginConfig(path, "pekko.persistence.r2dbc.journal", addColumn("event_journal", "col1"))
      writeEvent(config)
      columns(path, config, "EVENT_JOURNAL") should contain("COL1")
    }

    "run h2.additional-init of the snapshot plugin after creating its table" in {
      val path = "h2-tables-snapshot-init"
      val config = pluginConfig(path, "pekko.persistence.r2dbc.snapshot", addColumn("snapshot", "col1"))
      val dao = SnapshotDao.fromConfig(SnapshotSettings(config), config)(system, system.executionContext)
      dao.store(SerializedSnapshotRow("Entity|p-1", 1L, 0L, Array[Byte](1), 1, "", None)).futureValue
      columns(path, config, "SNAPSHOT") should contain("COL1")
    }

    "run h2.additional-init of the durable state plugin after creating its tables" in {
      val path = "h2-tables-state-init"
      val config = pluginConfig(path, "pekko.persistence.r2dbc.state", addColumn("durable_state", "col1"))
      val dao = DurableStateDao.fromConfig(StateSettings(config), config)(system, system.executionContext)
      dao.upsertState(stateRow("Entity|p-1"), "s").futureValue
      columns(path, config, "DURABLE_STATE") should contain("COL1")
    }

    "run the h2.additional-init of each plugin that uses the connection factory" in {
      val path = "h2-tables-two-plugins-init"
      def writeState(table: String, column: String): Config = {
        val config = pluginConfig(
          path,
          "pekko.persistence.r2dbc.state",
          s"""
            table = "$table"
            ${addColumn(table, column)}
            """)
        val dao = DurableStateDao.fromConfig(StateSettings(config), config)(system, system.executionContext)
        dao.upsertState(stateRow("Entity|p-1"), "s").futureValue
        config
      }
      writeState("state_a", "col_a")
      val config = writeState("state_b", "col_b")
      columns(path, config, "STATE_A") should (contain("COL_A") and not contain "COL_B")
      columns(path, config, "STATE_B") should (contain("COL_B") and not contain "COL_A")
    }
  }

  private def addColumn(table: String, column: String): String =
    s"""h2.additional-init = "ALTER TABLE $table ADD COLUMN IF NOT EXISTS $column VARCHAR(256)""""

  private def columns(path: String, config: Config, table: String): Seq[String] =
    executor(path, config)
      .select("select columns")(
        _.createStatement(s"SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '$table'"),
        _.get(0, classOf[String]))
      .futureValue
}
