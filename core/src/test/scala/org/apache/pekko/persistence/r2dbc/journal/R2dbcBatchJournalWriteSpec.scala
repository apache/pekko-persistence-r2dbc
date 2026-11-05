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

import scala.concurrent.duration._
import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.LoggingTestKit
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.testkit.typed.scaladsl.TestProbe
import pekko.actor.typed.ActorRef
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
import pekko.persistence.r2dbc.Dialect
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.internal.h2.H2Utils
import pekko.persistence.r2dbc.journal.mysql.MySQLJournalDao
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object R2dbcBatchJournalWriteSpec {

  // long batch window so that the writes in the tests are guaranteed to be coalesced into one batch flush
  val config: Config = ConfigFactory
    .parseString("pekko.persistence.r2dbc.batched-journal.max-batch-time = 1s")
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

  private def sendWrite(repr: PersistentRepr, replyTo: ActorRef[Any]): Unit =
    journal ! WriteMessages(Seq(AtomicWrite(repr)), replyTo.toClassic, actorInstanceId = 1)

  private def expectWriteSuccess(probe: TestProbe[Any], repr: PersistentRepr): Unit = {
    probe.expectMessage(10.seconds, WriteMessagesSuccessful)
    val success = probe.expectMessageType[WriteMessageSuccess](10.seconds)
    success.persistent.persistenceId shouldBe repr.persistenceId
    success.persistent.sequenceNr shouldBe repr.sequenceNr
    success.persistent.metadata shouldBe repr.metadata
  }

  private case class Row(pid: String, seqNr: Long, tags: Set[String])

  private def storedTags(): Map[(String, Long), Set[String]] =
    r2dbcExecutor
      .select[Row]("test")(
        connection =>
          connection.createStatement(
            s"select persistence_id, seq_nr, tags from ${journalSettings.journalTableWithSchema}"),
        row => {
          val tags =
            journalSettings.dialect match {
              case Dialect.MySQL | Dialect.MariaDB =>
                MySQLJournalDao.tagsFromJson(row.get("tags", classOf[String]))
              case Dialect.H2 =>
                H2Utils.tagsFromDb(row, "tags")
              case Dialect.Postgres | Dialect.Yugabyte =>
                row.get("tags", classOf[Array[String]]) match {
                  case null      => Set.empty[String]
                  case tagsArray => tagsArray.toSet
                }
            }
          Row(
            row.get("persistence_id", classOf[String]),
            row.get[java.lang.Long]("seq_nr", classOf[java.lang.Long]).longValue(),
            tags)
        })
      .futureValue
      .map(r => (r.pid, r.seqNr) -> r.tags)
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
        sendWrite(taggedA, probeA.ref)
        sendWrite(taggedB, probeB.ref)
        sendWrite(untagged, probeC.ref)

        expectWriteSuccess(probeA, taggedA)
        expectWriteSuccess(probeB, taggedB)
        expectWriteSuccess(probeC, untagged)
      }

      val tags = storedTags()
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
        sendWrite(withMeta, probeWithMeta.ref)
        sendWrite(withoutMeta, probeWithoutMeta.ref)

        expectWriteSuccess(probeWithMeta, withMeta)
        expectWriteSuccess(probeWithoutMeta, withoutMeta)
      }

      val receiverProbe = pekko.testkit.TestProbe()(system.classicSystem)
      journal ! ReplayMessages(1L, 1L, 1L, withMeta.persistenceId, receiverProbe.ref)
      receiverProbe.expectMsgPF() {
        case ReplayedMessage(replayed) =>
          replayed.sequenceNr shouldBe 1L
          replayed.metadata shouldBe Some("meta-data")
      }
      receiverProbe.expectMsgType[RecoverySuccess]

      journal ! ReplayMessages(1L, 1L, 1L, withoutMeta.persistenceId, receiverProbe.ref)
      receiverProbe.expectMsgPF() {
        case ReplayedMessage(replayed) =>
          replayed.sequenceNr shouldBe 1L
          replayed.metadata shouldBe None
      }
      receiverProbe.expectMsgType[RecoverySuccess]
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
      val replayed = (1 to 3).map(_ => receiverProbe.expectMsgType[ReplayedMessage].persistent.sequenceNr)
      replayed shouldBe Vector(1L, 2L, 3L)
      receiverProbe.expectMsgType[RecoverySuccess]
    }
  }
}
