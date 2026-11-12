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

package org.apache.pekko.persistence.r2dbc.journal

import java.time.Instant

import scala.concurrent.duration._

import io.r2dbc.spi.Row

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.LoggingTestKit
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.testkit.typed.scaladsl.TestProbe
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.persistence.AtomicWrite
import pekko.persistence.JournalProtocol.RecoverySuccess
import pekko.persistence.JournalProtocol.ReplayMessages
import pekko.persistence.JournalProtocol.ReplayedMessage
import pekko.persistence.JournalProtocol.WriteMessageSuccess
import pekko.persistence.JournalProtocol.WriteMessages
import pekko.persistence.JournalProtocol.WriteMessagesSuccessful
import pekko.persistence.PersistentRepr
import pekko.persistence.journal.Tagged
import pekko.persistence.query.NoOffset
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.query.TimestampOffset
import pekko.persistence.query.typed.EventEnvelope
import pekko.persistence.r2dbc.Dialect
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.internal.EnvelopeOrigin
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.stream.testkit.scaladsl.TestSink

import org.scalatest.wordspec.AnyWordSpecLike

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object R2dbcBatchJournalWriteSpec {

  // long batch window so that the writes in the tests are guaranteed to be coalesced into one batch flush
  val config: Config = ConfigFactory
    .parseString("""
      pekko.persistence.r2dbc {
        batched-journal {
          max-batch-time = 1s
          publish-events = off
        }
        query {
          buffer-size = 2
          behind-current-time = 0s
          backtracking.behind-current-time = 0s
        }
      }""")
    .withFallback(R2dbcBatchJournalSpec.config)
}

class R2dbcBatchJournalWriteSpec
    extends ScalaTestWithActorTestKit(R2dbcBatchJournalWriteSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[?] = system

  private lazy val journal = persistenceExt.journalFor("pekko.persistence.r2dbc.batched-journal")
  private implicit val dialect: Dialect = journalSettings.dialect

  private def expectWriteSuccess(probe: TestProbe[Any], repr: PersistentRepr): Unit = {
    probe.expectMessage(10.seconds, WriteMessagesSuccessful)
    val success = probe.expectMessageType[WriteMessageSuccess](10.seconds)
    success.persistent.persistenceId shouldBe repr.persistenceId
    success.persistent.sequenceNr shouldBe repr.sequenceNr
    success.persistent.metadata shouldBe repr.metadata
  }

  private case class TagRow(pid: String, seqNr: Long, tags: Set[String])

  private def selectByPids[A](pids: Seq[String], sql: String)(mapRow: Row => A): IndexedSeq[A] =
    r2dbcExecutor
      .select[A]("test")(
        connection =>
          pids.zipWithIndex.foldLeft(connection.createStatement(sql)) { case (stmt, (pid, i)) =>
            stmt.bind(i, pid)
          },
        mapRow)
      .futureValue

  private def inPids(pids: Seq[String]): String = pids.map(_ => "?").mkString(", ")

  private def storedTags(pids: Seq[String]): Map[(String, Long), Set[String]] =
    selectByPids[TagRow](
      pids,
      sql"select persistence_id, seq_nr, tags from ${journalSettings.journalTableWithSchema} where persistence_id in (${inPids(pids)})")(
      row =>
        TagRow(
          row.get("persistence_id", classOf[String]),
          row.get[java.lang.Long]("seq_nr", classOf[java.lang.Long]).longValue(),
          readTags(row)))
      .map(r => (r.pid, r.seqNr) -> r.tags)
      .toMap

  private def storedTimestamps(pids: Seq[String]): Map[String, Instant] =
    selectByPids[(String, Instant)](
      pids,
      sql"select persistence_id, db_timestamp from ${journalSettings.journalTableWithSchema} where persistence_id in (${inPids(pids)})")(
      row => row.get("persistence_id", classOf[String]) -> row.get("db_timestamp", classOf[Instant]))
      .toMap

  "R2dbcBatchJournal writes" should {

    "store the tags of tagged and untagged writes in one batch" in {
      val entityType = nextEntityType()
      val taggedA = PersistentRepr(Tagged("a1", Set("tag-a")), 1L, nextPid(entityType))
      val taggedB = PersistentRepr(Tagged("b1", Set("tag-b1", "tag-b2")), 1L, nextPid(entityType))
      val untagged = PersistentRepr("c1", 1L, nextPid(entityType))

      // all three arrive within the 1 second batch window and are flushed as one batch
      val probeA = createTestProbe[Any]()
      val probeB = createTestProbe[Any]()
      val probeC = createTestProbe[Any]()
      LoggingTestKit.debug("flushing [3] write requests").expect {
        journal ! batchedJournalWrite(taggedA, probeA.ref)
        journal ! batchedJournalWrite(taggedB, probeB.ref)
        journal ! batchedJournalWrite(untagged, probeC.ref)

        expectWriteSuccess(probeA, taggedA)
        expectWriteSuccess(probeB, taggedB)
        expectWriteSuccess(probeC, untagged)
      }

      val tags = storedTags(Seq(taggedA.persistenceId, taggedB.persistenceId, untagged.persistenceId))
      tags(taggedA.persistenceId -> 1L) shouldBe Set("tag-a")
      tags(taggedB.persistenceId -> 1L) shouldBe Set("tag-b1", "tag-b2")
      tags(untagged.persistenceId -> 1L) shouldBe Set.empty
    }

    "round-trip metadata" in {
      val entityType = nextEntityType()
      val withMeta = PersistentRepr("m1", 1L, nextPid(entityType)).withMetadata("meta-data")
      val withoutMeta = PersistentRepr("m2", 1L, nextPid(entityType))

      val probeWithMeta = createTestProbe[Any]()
      val probeWithoutMeta = createTestProbe[Any]()
      LoggingTestKit.debug("flushing [2] write requests").expect {
        journal ! batchedJournalWrite(withMeta, probeWithMeta.ref)
        journal ! batchedJournalWrite(withoutMeta, probeWithoutMeta.ref)

        expectWriteSuccess(probeWithMeta, withMeta)
        expectWriteSuccess(probeWithoutMeta, withoutMeta)
      }

      val receiverProbe = pekko.testkit.TestProbe()(system.classicSystem)
      journal ! ReplayMessages(1L, 1L, 1L, withMeta.persistenceId, receiverProbe.ref)
      receiverProbe.expectMsgPF(10.seconds) {
        case ReplayedMessage(replayed) =>
          replayed.sequenceNr shouldBe 1L
          replayed.metadata shouldBe Some("meta-data")
      }
      receiverProbe.expectMsgType[RecoverySuccess](10.seconds)

      journal ! ReplayMessages(1L, 1L, 1L, withoutMeta.persistenceId, receiverProbe.ref)
      receiverProbe.expectMsgPF(10.seconds) {
        case ReplayedMessage(replayed) =>
          replayed.sequenceNr shouldBe 1L
          replayed.metadata shouldBe None
      }
      receiverProbe.expectMsgType[RecoverySuccess](10.seconds)
    }

    "write all events of a persistAll burst" in {
      val entityType = nextEntityType()
      val pid = nextPid(entityType)
      val atomic = AtomicWrite(
        Seq(
          PersistentRepr("a", 1L, pid),
          PersistentRepr("b", 2L, pid),
          PersistentRepr("c", 3L, pid)))

      val probe = createTestProbe[Any]()
      journal ! WriteMessages(Seq(atomic), probe.ref.toClassic, actorInstanceId = 1)

      probe.expectMessage(10.seconds, WriteMessagesSuccessful)
      val seqNrs = (1 to 3).map(_ => probe.expectMessageType[WriteMessageSuccess](10.seconds).persistent.sequenceNr)
      seqNrs shouldBe Vector(1L, 2L, 3L)

      val receiverProbe = pekko.testkit.TestProbe()(system.classicSystem)
      journal ! ReplayMessages(1L, 3L, 3L, pid, receiverProbe.ref)
      val replayed = (1 to 3).map(_ => receiverProbe.expectMsgType[ReplayedMessage](10.seconds).persistent.sequenceNr)
      replayed shouldBe Vector(1L, 2L, 3L)
      receiverProbe.expectMsgType[RecoverySuccess](10.seconds)
    }

    "deliver the events of a coalesced batch to eventsBySlices with strictly increasing timestamps" in {
      val entityType = nextEntityType()
      val reprs = (0 until 3).map(i => PersistentRepr(s"e-$i", 1L, nextPid(entityType)))
      val probes = reprs.map(_ => createTestProbe[Any]())

      LoggingTestKit.debug("flushing [3] write requests").expect {
        reprs.zip(probes).foreach {
          case (repr, probe) => journal ! batchedJournalWrite(repr, probe.ref)
        }
        reprs.zip(probes).foreach {
          case (repr, probe) => expectWriteSuccess(probe, repr)
        }
      }

      val pids = reprs.map(_.persistenceId)
      val stored = storedTimestamps(pids)
      stored(pids(0)).isBefore(stored(pids(1))) shouldBe true
      stored(pids(1)).isBefore(stored(pids(2))) shouldBe true

      val query = PersistenceQuery(system.classicSystem).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)
      val sinkProbe = TestSink[EventEnvelope[String]]()(system.classicSystem)
      val result = query
        .eventsBySlices[String](entityType, 0, persistenceExt.numberOfSlices - 1, NoOffset)
        .runWith(sinkProbe)
        .request(10)

      reprs.take(2).zipWithIndex.foreach {
        case (repr, i) =>
          val envelope = result.expectNext(10.seconds)
          envelope.source shouldBe EnvelopeOrigin.SourceQuery
          envelope.persistenceId shouldBe repr.persistenceId
          envelope.sequenceNr shouldBe 1L
          envelope.event shouldBe s"e-$i"
          envelope.offset.asInstanceOf[TimestampOffset].timestamp shouldBe stored(repr.persistenceId)
      }

      reprs.take(2).foreach { repr =>
        val btEnvelope = result.expectNext(10.seconds)
        btEnvelope.source shouldBe EnvelopeOrigin.SourceBacktracking
        btEnvelope.persistenceId shouldBe repr.persistenceId
        btEnvelope.sequenceNr shouldBe 1L
        btEnvelope.eventOption shouldBe None
        btEnvelope.offset.asInstanceOf[TimestampOffset].timestamp shouldBe stored(repr.persistenceId)
      }

      val lastEnvelope = result.expectNext(10.seconds)
      lastEnvelope.source shouldBe EnvelopeOrigin.SourceQuery
      lastEnvelope.persistenceId shouldBe reprs(2).persistenceId
      lastEnvelope.sequenceNr shouldBe 1L
      lastEnvelope.event shouldBe "e-2"
      lastEnvelope.offset.asInstanceOf[TimestampOffset].timestamp shouldBe stored(reprs(2).persistenceId)

      result.cancel()
    }
  }
}
