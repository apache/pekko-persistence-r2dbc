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
import java.util.UUID

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

import org.apache.pekko
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.SnapshotSelectionCriteria
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.r2dbc.SnapshotSettings
import pekko.persistence.r2dbc.TestActors.Persister
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.internal.InstantFactory
import pekko.persistence.r2dbc.journal.JournalDao
import pekko.persistence.r2dbc.journal.JournalDao.SerializedEventMetadata
import pekko.persistence.r2dbc.journal.JournalDao.SerializedJournalRow
import pekko.persistence.r2dbc.query.scaladsl.QueryDao
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.persistence.r2dbc.snapshot.SnapshotDao
import pekko.persistence.r2dbc.snapshot.SnapshotDao.SerializedSnapshotMetadata
import pekko.persistence.r2dbc.snapshot.SnapshotDao.SerializedSnapshotRow
import pekko.persistence.r2dbc.state.scaladsl.DurableStateDao
import pekko.persistence.r2dbc.state.scaladsl.R2dbcDurableStateStore
import pekko.persistence.state.DurableStateStoreRegistry
import pekko.persistence.state.scaladsl.GetObjectResult
import pekko.persistence.typed.PersistenceId
import pekko.stream.scaladsl.Sink
import com.typesafe.config.ConfigFactory
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

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(10.seconds)

  private lazy val journalDao =
    JournalDao.fromConfig(journalSettings, ConfigFactory.empty())(system, system.executionContext)
  private lazy val queryDao =
    QueryDao.fromConfig(querySettings, ConfigFactory.empty())(system, system.executionContext)
  private lazy val stateDao =
    DurableStateDao.fromConfig(stateSettings, ConfigFactory.empty())(system, system.executionContext)
  // with the slice queries of the snapshots as starting points
  private lazy val snapshotBySliceDao = SnapshotDao.fromConfig(
    SnapshotSettings(
      ConfigFactory
        .parseString("start-from-snapshot.enabled = on")
        .withFallback(system.settings.config.getConfig("pekko.persistence.r2dbc.snapshot"))),
    ConfigFactory.empty())(system, system.executionContext)
  private lazy val stateStore = DurableStateStoreRegistry(system)
    .durableStateStoreFor[R2dbcDurableStateStore[String]](R2dbcDurableStateStore.Identifier)

  // a persistence id without the entity type separator has an empty entity type, which Oracle stores as NULL
  private def nextPidWithoutEntityType(): String = s"no-entity-type-${UUID.randomUUID()}"

  private def slice(persistenceId: String): Int = persistenceExt.sliceForPersistenceId(persistenceId)

  private def journalRow(
      persistenceId: String,
      seqNr: Long,
      payload: Array[Byte],
      metadata: Option[SerializedEventMetadata]): SerializedJournalRow =
    SerializedJournalRow(slice(persistenceId), PersistenceId.extractEntityType(persistenceId), persistenceId, seqNr,
      InstantFactory.now(), Instant.EPOCH, Some(payload), 1, "", "writer", Set.empty, metadata)

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
        .store(SerializedSnapshotRow(1, "Entity", persistenceId, 1L, Instant.EPOCH, 1000L, payload1, 1, "", Set.empty,
          None))
        .futureValue
      val loaded1 = dao.load(persistenceId, SnapshotSelectionCriteria.Latest).futureValue.get
      loaded1.seqNr shouldBe 1L
      loaded1.snapshot.toSeq shouldBe payload1.toSeq
      loaded1.serializerManifest shouldBe ""
      loaded1.metadata shouldBe None

      dao
        .store(SerializedSnapshotRow(1, "Entity", persistenceId, 2L, Instant.EPOCH, 2000L, payload2, 1, "m2", Set.empty,
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

    "query events, snapshots and durable state with an empty entity type" in {
      val persistenceId = nextPidWithoutEntityType()
      val s = slice(persistenceId)
      journalDao.writeEvents(Seq(journalRow(persistenceId, 1L, Array[Byte](1), None))).futureValue
      snapshotBySliceDao
        .store(SerializedSnapshotRow(s, "", persistenceId, 1L, Instant.now(), 1000L, Array[Byte](2), 1, "m", Set.empty,
          None))
        .futureValue
      stateStore.upsertObject(persistenceId, 1L, "s1", "").futureValue

      def bySlices[R](rows: pekko.stream.scaladsl.Source[R, ?]): Seq[R] = rows.runWith(Sink.seq).futureValue

      bySlices(queryDao.rowsBySlices("", s, s, Instant.EPOCH, None, Duration.Zero, backtracking = false))
        .map(r => (r.persistenceId, r.entityType)) shouldBe Seq(persistenceId -> "")
      bySlices(queryDao.rowsBySlices("", s, s, Instant.EPOCH, None, Duration.Zero, backtracking = true))
        .map(_.persistenceId) shouldBe Seq(persistenceId)
      queryDao.countBuckets("", s, s, Instant.EPOCH, 10).futureValue.map(_.count).sum shouldBe 1L

      bySlices(snapshotBySliceDao.rowsBySlices("", s, s, Instant.EPOCH, None, Duration.Zero, backtracking = false))
        .map(_.persistenceId) shouldBe Seq(persistenceId)
      snapshotBySliceDao.countBuckets("", s, s, Instant.EPOCH, 10).futureValue.map(_.count).sum shouldBe 1L

      bySlices(stateDao.rowsBySlices("", s, s, Instant.EPOCH, None, Duration.Zero, backtracking = false))
        .map(_.persistenceId) shouldBe Seq(persistenceId)
      stateDao.countBuckets("", s, s, Instant.EPOCH, 10).futureValue.map(_.count).sum shouldBe 1L

      queryDao.loadEvent(persistenceId, 1L).futureValue.map(_.entityType) shouldBe Some("")
    }

    "read event and snapshot metadata with an empty payload, which Oracle stores as NULL" in {
      val persistenceId = nextPid(nextEntityType())
      journalDao
        .writeEvents(
          Seq(journalRow(persistenceId, 1L, Array[Byte](1),
            Some(SerializedEventMetadata(7, "", Array.emptyByteArray)))))
        .futureValue
      val event = queryDao.loadEvent(persistenceId, 1L).futureValue.get
      event.metadata.map(m => (m.serId, m.serManifest, m.payload.toSeq)) shouldBe Some((7, "", Seq.empty[Byte]))
      queryDao
        .eventsByPersistenceId(persistenceId, 0L, Long.MaxValue)
        .runWith(Sink.seq)
        .futureValue
        .map(_.metadata.map(_.payload.toSeq)) shouldBe Seq(Some(Seq.empty[Byte]))

      val snapshotDao =
        SnapshotDao.fromConfig(snapshotSettings, system.settings.config)(system, system.executionContext)
      snapshotDao
        .store(SerializedSnapshotRow(slice(persistenceId), PersistenceId.extractEntityType(persistenceId),
          persistenceId,
          1L, Instant.EPOCH, 1000L, Array[Byte](1), 1, "", Set.empty,
          Some(SerializedSnapshotMetadata(Array.emptyByteArray, 7, ""))))
        .futureValue
      snapshotDao
        .load(persistenceId, SnapshotSelectionCriteria.Latest)
        .futureValue
        .flatMap(_.metadata)
        .map(m => (m.payload.toSeq, m.serializerId, m.serializerManifest)) shouldBe Some((Seq.empty[Byte], 7, ""))
    }

    "let the last of concurrent first saves of a snapshot win" in {
      val dao = SnapshotDao.fromConfig(snapshotSettings, system.settings.config)(system, system.executionContext)
      val persistenceIds = (1 to 20).map(_ => nextPid(nextEntityType()))
      def row(persistenceId: String, seqNr: Long) =
        SerializedSnapshotRow(slice(persistenceId), PersistenceId.extractEntityType(persistenceId), persistenceId,
          seqNr, Instant.EPOCH, seqNr, Array[Byte](seqNr.toByte), 1, "", Set.empty, None)

      // without a row, both MERGE statements can take WHEN NOT MATCHED, and one violates the primary key
      implicit val ec: ExecutionContext = system.executionContext
      Future
        .sequence(persistenceIds.flatMap(pid => Seq(dao.store(row(pid, 1L)), dao.store(row(pid, 2L)))))
        .futureValue

      persistenceIds.foreach { pid =>
        Set(1L, 2L) should contain(dao.load(pid, SnapshotSelectionCriteria.Latest).futureValue.get.seqNr)
      }
    }

    "store and read large event and durable state payloads" in {
      val large = "x" * 100000
      val persistenceId = nextPid(nextEntityType())
      val persister = spawn(Persister(persistenceId))
      val probe = createTestProbe[Done]()
      persister ! Persister.PersistWithAck(large, probe.ref)
      probe.expectMessage(Done)
      PersistenceQuery(system)
        .readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)
        .currentEventsByPersistenceId(persistenceId, 0L, Long.MaxValue)
        .runWith(Sink.seq)
        .futureValue
        .map(_.event) shouldBe Seq(large)

      val statePersistenceId = nextPid(nextEntityType())
      stateStore.upsertObject(statePersistenceId, 1L, large, "").futureValue
      stateStore.upsertObject(statePersistenceId, 2L, large + "y", "").futureValue
      stateStore.getObject(statePersistenceId).futureValue shouldBe GetObjectResult(Some(large + "y"), 2L)
    }

    "not change how user statements bind byte arrays and read NULL" in {
      val table = "oracle_adapter_test"
      r2dbcExecutor
        .executeDdl("create table")(
          _.createStatement(s"CREATE TABLE IF NOT EXISTS $table (id NUMBER(10), writer VARCHAR2(10), data RAW(16))"))
        .futureValue
      r2dbcExecutor.updateOne("delete")(_.createStatement(s"DELETE FROM $table")).futureValue
      r2dbcExecutor
        .updateOne("insert")(
          _.createStatement(s"INSERT INTO $table (id, writer, data) VALUES (?, ?, ?)")
            .bind(0, 1)
            .bind(1, "")
            .bind(2, Array[Byte](1, 2, 3)))
        .futureValue

      // a byte array is bound as RAW, and the plugin's empty values aren't applied to the columns of user tables
      r2dbcExecutor
        .selectOne("select")(
          _.createStatement(s"SELECT writer FROM $table WHERE data = ?").bind(0, Array[Byte](1, 2, 3)),
          row => Option(row.get("writer", classOf[String])))
        .futureValue shouldBe Some(None)
    }
  }
}
