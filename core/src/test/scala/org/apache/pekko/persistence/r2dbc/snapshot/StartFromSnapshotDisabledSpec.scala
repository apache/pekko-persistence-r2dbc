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

import org.apache.pekko
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.query.NoOffset
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.r2dbc.Dialect
import pekko.persistence.r2dbc.TestActors.Persister
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.query.javadsl.{ R2dbcReadJournal => JavadslR2dbcReadJournal }
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.persistence.typed.PersistenceId
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object StartFromSnapshotDisabledSpec {
  val legacyTable = "snapshot_legacy"

  // start-from-snapshot.enabled is off by default
  val config: Config = ConfigFactory.load(
    ConfigFactory
      .parseString(s"""
    pekko.persistence.r2dbc.snapshot.table = "$legacyTable"
    """)
      .withFallback(TestConfig.unresolvedConfig))
}

/**
 * The snapshot table without the db_timestamp and tags columns, i.e. before running the migration script, must keep
 * working as long as start-from-snapshot is not enabled.
 */
class StartFromSnapshotDisabledSpec
    extends ScalaTestWithActorTestKit(StartFromSnapshotDisabledSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {
  import StartFromSnapshotDisabledSpec.legacyTable

  override def typedSystem: ActorSystem[?] = system

  private val query = PersistenceQuery(testKit.system).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)
  private val javadslQuery =
    PersistenceQuery(testKit.system).getReadJournalFor(classOf[JavadslR2dbcReadJournal], R2dbcReadJournal.Identifier)

  private def createLegacyTableSql: String = {
    val payloadType =
      if (snapshotSettings.dialect == Dialect.MySQL || snapshotSettings.dialect == Dialect.MariaDB) "BLOB" else "BYTEA"
    // nullable like in the Oracle DDL, since Oracle stores the empty string as NULL
    if (snapshotSettings.dialect == Dialect.Oracle)
      s"""CREATE TABLE IF NOT EXISTS $legacyTable(
        slice NUMBER(10) NOT NULL,
        entity_type VARCHAR2(255),
        persistence_id VARCHAR2(255) NOT NULL,
        seq_nr NUMBER(19) NOT NULL,
        write_timestamp NUMBER(19) NOT NULL,
        ser_id NUMBER(10) NOT NULL,
        ser_manifest VARCHAR2(255),
        snapshot BLOB NOT NULL,
        meta_ser_id NUMBER(10),
        meta_ser_manifest VARCHAR2(255),
        meta_payload BLOB,
        PRIMARY KEY(persistence_id))"""
    else
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

  override protected def beforeAll(): Unit = {
    r2dbcExecutor.executeDdl("create legacy snapshot table")(_.createStatement(createLegacyTableSql)).futureValue
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    r2dbcExecutor.executeDdl("drop legacy snapshot table")(_.createStatement(s"DROP TABLE $legacyTable")).futureValue
    super.afterAll()
  }

  "Snapshot store with start-from-snapshot disabled" should {
    "store and load snapshots in a table without the db_timestamp and tags columns" in {
      val persistenceId = PersistenceId.ofUniqueId(nextPid(nextEntityType()))
      val probe = createTestProbe[Done]()
      val snapshotAckProbe = createTestProbe[Long]()

      val persister = spawn(Persister.withSnapshotAck(persistenceId, Set("tag-A"), snapshotAckProbe.ref))
      persister ! Persister.PersistWithAck("e-1", probe.ref)
      probe.expectMessage(Done)
      persister ! Persister.PersistWithAck("e-2-snap", probe.ref)
      snapshotAckProbe.expectMessage(2L)
      probe.expectMessage(Done)
      testKit.stop(persister)

      val count = r2dbcExecutor
        .selectOne("count legacy snapshots")(
          _.createStatement(s"SELECT COUNT(*) AS cnt FROM $legacyTable WHERE persistence_id = '${persistenceId.id}'"),
          row => row.get("cnt", classOf[java.lang.Long]).longValue)
        .futureValue
      count shouldBe Some(1L)

      val persister2 = spawn(Persister(persistenceId))
      val stateProbe = createTestProbe[String]()
      persister2 ! Persister.GetState(stateProbe.ref)
      stateProbe.expectMessage("e-1|e-2-snap")
    }
  }

  "Queries starting from snapshots" should {
    "be rejected when start-from-snapshot is disabled" in {
      val entityType = nextEntityType()
      val pid = nextPid(entityType)
      val transform: String => String = identity

      intercept[IllegalArgumentException] {
        query.eventsBySlicesStartingFromSnapshots(entityType, 0, 1023, NoOffset, transform)
      }.getMessage should include("pekko.persistence.r2dbc.query.start-from-snapshot.enabled")
      intercept[IllegalArgumentException] {
        query.currentEventsBySlicesStartingFromSnapshots(entityType, 0, 1023, NoOffset, transform)
      }
      intercept[IllegalArgumentException] {
        query.eventsByPersistenceIdStartingFromSnapshot(pid, 0L, Long.MaxValue, transform)
      }
      intercept[IllegalArgumentException] {
        query.currentEventsByPersistenceIdStartingFromSnapshot(pid, 0L, Long.MaxValue, transform)
      }
      intercept[IllegalArgumentException] {
        javadslQuery.eventsBySlicesStartingFromSnapshots[String, String](entityType, 0, 1023, NoOffset,
          (snap: String) => snap)
      }
      intercept[IllegalArgumentException] {
        javadslQuery.currentEventsByPersistenceIdStartingFromSnapshot[String, String](pid, 0L, Long.MaxValue,
          (snap: String) => snap)
      }
    }
  }
}
