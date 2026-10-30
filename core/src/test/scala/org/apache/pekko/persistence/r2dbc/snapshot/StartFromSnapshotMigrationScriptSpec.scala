/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.persistence.r2dbc.snapshot

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

import org.apache.pekko
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.query.NoOffset
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.query.TimestampOffset
import pekko.persistence.r2dbc.Dialect
import pekko.persistence.r2dbc.TestActors.Persister
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.cleanup.scaladsl.EventSourcedCleanup
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.persistence.typed.PersistenceId
import pekko.stream.scaladsl.Sink
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object StartFromSnapshotMigrationScriptSpec {
  val legacyTable = "snapshot_migration_legacy"

  // the snapshot plugin writes with start-from-snapshot disabled, as before the migration, and the query is enabled
  val config: Config = ConfigFactory.load(
    ConfigFactory
      .parseString(s"""
    pekko.persistence.r2dbc.snapshot.table = "$legacyTable"
    pekko.persistence.r2dbc.snapshot.start-from-snapshot.enabled = off
    pekko.persistence.r2dbc.query.start-from-snapshot.enabled = on
    """)
      .withFallback(TestConfig.unresolvedConfig))
}

/**
 * Runs the migration script in `ddl-scripts/migration` of the dialect on a snapshot table without the db_timestamp and
 * tags columns, and verifies that the queries that start from snapshots emit the migrated snapshots.
 */
class StartFromSnapshotMigrationScriptSpec
    extends ScalaTestWithActorTestKit(StartFromSnapshotMigrationScriptSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {
  import StartFromSnapshotMigrationScriptSpec.legacyTable

  override def typedSystem: ActorSystem[?] = system

  private val dialect = snapshotSettings.dialect

  // there is no migration script for H2, the plugin creates the H2 tables
  private val scriptFileName: Option[String] = dialect match {
    case Dialect.Postgres                => Some("snapshot_start_from_snapshot_postgres.sql")
    case Dialect.Yugabyte                => Some("snapshot_start_from_snapshot_yugabyte.sql")
    case Dialect.MySQL | Dialect.MariaDB => Some("snapshot_start_from_snapshot_mysql.sql")
    case _                               => None
  }

  private def createLegacyTableSql: String = {
    val payloadType = if (dialect == Dialect.MySQL || dialect == Dialect.MariaDB) "BLOB" else "BYTEA"
    s"""CREATE TABLE IF NOT EXISTS $legacyTable(
      slice INT NOT NULL,
      entity_type VARCHAR(255) NOT NULL,
      persistence_id VARCHAR(255) NOT NULL,
      seq_nr BIGINT NOT NULL,
      write_timestamp BIGINT NOT NULL,
      ser_id INTEGER NOT NULL,
      ser_manifest VARCHAR(255) NOT NULL,
      snapshot $payloadType NOT NULL,
      meta_ser_id INTEGER,
      meta_ser_manifest VARCHAR(255),
      meta_payload $payloadType,
      PRIMARY KEY(persistence_id))"""
  }

  private def scriptFile(fileName: String): Path = {
    // the working directory is the root project in sbt, but can be the module directory in an IDE
    val candidates = List(Paths.get("ddl-scripts", "migration", fileName), Paths.get("..", "ddl-scripts", "migration", fileName))
    candidates.find(Files.exists(_)).getOrElse(fail(s"Migration script [$fileName] not found"))
  }

  /** The statements of the script, applied to the legacy table instead of the `snapshot` table. */
  private def scriptStatements(fileName: String): List[String] = {
    val script = new String(Files.readAllBytes(scriptFile(fileName)), StandardCharsets.UTF_8)
    script
      .split("\n")
      .filterNot(_.trim.startsWith("--"))
      .mkString("\n")
      .split(";")
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { statement =>
        statement
          .replace("snapshot_slice_idx", s"${legacyTable}_slice_idx")
          .replaceAll("\\bsnapshot\\b", legacyTable)
      }
      .toList
  }

  override protected def beforeAll(): Unit = {
    if (scriptFileName.isDefined)
      r2dbcExecutor.executeDdl("create legacy snapshot table")(_.createStatement(createLegacyTableSql)).futureValue
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    if (scriptFileName.isDefined)
      r2dbcExecutor.executeDdl("drop legacy snapshot table")(_.createStatement(s"DROP TABLE $legacyTable")).futureValue
    super.afterAll()
  }

  private def persistWithSnapshot(persistenceId: PersistenceId, events: List[String], tags: Set[String]): Unit = {
    val probe = createTestProbe[Done]()
    val snapshotAckProbe = createTestProbe[Long]()
    val persister = spawn(Persister.withSnapshotAck(persistenceId, tags, snapshotAckProbe.ref))
    events.foreach { event =>
      persister ! Persister.PersistWithAck(event, probe.ref)
      if (event.contains("snap"))
        snapshotAckProbe.expectMessageType[Long]
      probe.expectMessage(Done)
    }
    testKit.stop(persister)
  }

  private def writeTimestamp(persistenceId: PersistenceId): Instant =
    r2dbcExecutor
      .selectOne("select write_timestamp")(
        _.createStatement(s"SELECT write_timestamp FROM $legacyTable WHERE persistence_id = '${persistenceId.id}'"),
        row => Instant.ofEpochMilli(row.get("write_timestamp", classOf[java.lang.Long]).longValue))
      .futureValue
      .get

  "The start-from-snapshot migration script" should {
    "populate the snapshots so that the queries that start from snapshots emit them" in {
      assume(scriptFileName.isDefined, s"no migration script for [$dialect]")

      val entityType = nextEntityType()
      // the event of the snapshot exists
      val withEvent = PersistenceId(entityType, "with-event")
      // the event of the snapshot is replaced by a delete marker
      val deletedToSnapshot = PersistenceId(entityType, "deleted-to-snapshot")
      // the events are deleted past the snapshot, the delete marker is at a later seqNr
      val deletedPastSnapshot = PersistenceId(entityType, "deleted-past-snapshot")

      persistWithSnapshot(withEvent, List("a-1", "a-2-snap"), Set("tag-a"))
      persistWithSnapshot(deletedToSnapshot, List("b-1", "b-2-snap"), Set.empty)
      persistWithSnapshot(deletedPastSnapshot, List("c-1", "c-2-snap", "c-3"), Set.empty)

      val cleanup = new EventSourcedCleanup(system)
      cleanup.deleteEventsTo(deletedToSnapshot.id, 2L).futureValue
      cleanup.deleteEventsTo(deletedPastSnapshot.id, 3L).futureValue

      val query = PersistenceQuery(system).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)
      val eventTimestamp = query
        .currentEventsByPersistenceId(withEvent.id, 2L, 2L)
        .runWith(Sink.head)
        .futureValue
        .offset
        .asInstanceOf[TimestampOffset]
        .timestamp

      scriptStatements(scriptFileName.get).foreach { statement =>
        r2dbcExecutor.executeDdl("migration script")(_.createStatement(statement)).futureValue
      }

      val envelopes = query
        .currentEventsBySlicesStartingFromSnapshots[String, String](entityType, 0, 1023, NoOffset,
          snap => s"snapshot-$snap")
        .runWith(Sink.seq)
        .futureValue

      envelopes.map(env => env.persistenceId -> env.event).toSet shouldBe Set(
        withEvent.id -> "snapshot-a-1|a-2-snap",
        deletedToSnapshot.id -> "snapshot-b-1|b-2-snap",
        deletedPastSnapshot.id -> "snapshot-c-1|c-2-snap")

      def snapshotEnvelope(persistenceId: PersistenceId) = envelopes.find(_.persistenceId == persistenceId.id).get
      def snapshotTimestamp(persistenceId: PersistenceId) =
        snapshotEnvelope(persistenceId).offset.asInstanceOf[TimestampOffset].timestamp

      // the event's timestamp and tags when the event exists, otherwise the snapshot's write timestamp
      snapshotTimestamp(withEvent) shouldBe eventTimestamp
      snapshotEnvelope(withEvent).tags shouldBe Set("tag-a")
      snapshotTimestamp(deletedToSnapshot) shouldBe writeTimestamp(deletedToSnapshot)
      snapshotEnvelope(deletedToSnapshot).tags shouldBe Set.empty
      snapshotTimestamp(deletedPastSnapshot) shouldBe writeTimestamp(deletedPastSnapshot)
      snapshotEnvelope(deletedPastSnapshot).tags shouldBe Set.empty
    }
  }
}
