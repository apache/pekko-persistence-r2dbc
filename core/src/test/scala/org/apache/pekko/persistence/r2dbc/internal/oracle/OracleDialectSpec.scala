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

package org.apache.pekko.persistence.r2dbc.internal.oracle

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.apache.pekko
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.SnapshotSelectionCriteria
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.r2dbc.TestActors.Persister
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.persistence.r2dbc.snapshot.SnapshotDao
import pekko.persistence.r2dbc.snapshot.SnapshotDao.SerializedSnapshotMetadata
import pekko.persistence.r2dbc.snapshot.SnapshotDao.SerializedSnapshotRow
import pekko.persistence.r2dbc.state.scaladsl.R2dbcDurableStateStore
import pekko.persistence.state.DurableStateStoreRegistry
import pekko.persistence.state.scaladsl.GetObjectResult
import pekko.persistence.typed.PersistenceId
import pekko.stream.scaladsl.Sink
import org.scalatest.Outcome
import org.scalatest.Pending
import org.scalatest.wordspec.AnyWordSpecLike

class OracleDialectSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  private val isOracle = system.settings.config.getString("pekko.persistence.r2dbc.dialect") == "oracle"

  override def withFixture(test: NoArgTest): Outcome =
    if (isOracle) super.withFixture(test)
    else Pending

  override protected def beforeAll(): Unit =
    if (isOracle) super.beforeAll()

  override def typedSystem: ActorSystem[?] = system

  private def selectOne[A](sql: String, persistenceId: String)(column: io.r2dbc.spi.Row => A): A =
    r2dbcExecutor
      .selectOne("select")(_.createStatement(sql).bind(0, persistenceId), column)
      .futureValue
      .get

  "The Oracle dialect" should {

    "read an empty event serializer manifest, which Oracle stores as NULL, as an empty string" in {
      val persistenceId = nextPid()
      val persister = spawn(Persister(persistenceId))
      val probe = createTestProbe[Done]()
      persister ! Persister.PersistWithAck("e1", probe.ref)
      probe.expectMessage(Done)

      // the String serializer has no manifest
      selectOne(
        s"SELECT event_ser_manifest FROM ${journalSettings.journalTableWithSchema} WHERE persistence_id = ?",
        persistenceId)(row => Option(row.get(0, classOf[String]))) shouldBe None

      val events = PersistenceQuery(system)
        .readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)
        .currentEventsByPersistenceId(persistenceId, 0L, Long.MaxValue)
        .runWith(Sink.seq)
        .futureValue
      events.map(_.event) shouldBe Seq("e1")
    }

    "store and read db_timestamp as an Instant with microsecond precision" in {
      val persistenceId = nextPid()
      val before = Instant.now().truncatedTo(ChronoUnit.MICROS)
      val persister = spawn(Persister(persistenceId))
      val probe = createTestProbe[Done]()
      persister ! Persister.PersistWithAck("e1", probe.ref)
      probe.expectMessage(Done)
      val after = Instant.now()

      val dbTimestamp = selectOne(
        s"SELECT db_timestamp FROM ${journalSettings.journalTableWithSchema} WHERE persistence_id = ?",
        persistenceId)(_.get("db_timestamp", classOf[Instant]))
      dbTimestamp.isBefore(before) shouldBe false
      dbTimestamp.isAfter(after) shouldBe false
      dbTimestamp shouldBe dbTimestamp.truncatedTo(ChronoUnit.MICROS)
    }

    "insert and update a large snapshot with MERGE" in {
      val dao = SnapshotDao.fromConfig(snapshotSettings, system.settings.config)(system, system.executionContext)
      val persistenceId = nextPid()
      // larger than the RAW limit that applies to values bound in the SELECT of a MERGE
      val payload1 = Array.fill[Byte](100000)(1)
      val payload2 = Array.fill[Byte](200000)(2)

      dao
        .store(SerializedSnapshotRow(persistenceId, 1L, 1000L, payload1, 1, "", None))
        .futureValue
      val loaded1 = dao.load(persistenceId, SnapshotSelectionCriteria.Latest).futureValue.get
      loaded1.seqNr shouldBe 1L
      loaded1.snapshot.toSeq shouldBe payload1.toSeq
      loaded1.serializerManifest shouldBe ""
      loaded1.metadata shouldBe None

      dao
        .store(SerializedSnapshotRow(persistenceId, 2L, 2000L, payload2, 1, "m2",
          Some(SerializedSnapshotMetadata(Array[Byte](3), 1, ""))))
        .futureValue
      val loaded2 = dao.load(persistenceId, SnapshotSelectionCriteria.Latest).futureValue.get
      loaded2.seqNr shouldBe 2L
      loaded2.writeTimestamp shouldBe 2000L
      loaded2.snapshot.toSeq shouldBe payload2.toSeq
      loaded2.serializerManifest shouldBe "m2"
      loaded2.metadata.map(m => (m.payload.toSeq, m.serializerId, m.serializerManifest)) shouldBe
      Some((Seq[Byte](3), 1, ""))
    }

    "delete durable state, which stores an empty payload that Oracle stores as NULL" in {
      val store = DurableStateStoreRegistry(system)
        .durableStateStoreFor[R2dbcDurableStateStore[String]](R2dbcDurableStateStore.Identifier)
      val persistenceId = PersistenceId(nextEntityType(), "p1").id

      store.upsertObject(persistenceId, 1L, "s1", "").futureValue
      store.getObject(persistenceId).futureValue shouldBe GetObjectResult(Some("s1"), 1L)

      store.deleteObject(persistenceId, 2L).futureValue
      store.getObject(persistenceId).futureValue shouldBe GetObjectResult(None, 2L)
    }
  }
}
