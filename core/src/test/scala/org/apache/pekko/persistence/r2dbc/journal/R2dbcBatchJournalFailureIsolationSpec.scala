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
import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.testkit.typed.scaladsl.TestProbe
import pekko.actor.typed.ActorRef
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.persistence.AtomicWrite
import pekko.persistence.JournalProtocol.WriteMessageFailure
import pekko.persistence.JournalProtocol.WriteMessageSuccess
import pekko.persistence.JournalProtocol.WriteMessages
import pekko.persistence.JournalProtocol.WriteMessagesFailed
import pekko.persistence.JournalProtocol.WriteMessagesSuccessful
import pekko.persistence.PersistentRepr
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.internal.PayloadCodec
import pekko.persistence.r2dbc.internal.PayloadCodec.RichRow
import pekko.serialization.SerializationExtension
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import org.scalatest.wordspec.AnyWordSpecLike

object R2dbcBatchJournalFailureIsolationSpec {

  // long batch window so that the concurrent writes in the test are guaranteed
  // to be coalesced into one batch flush
  val config: Config = ConfigFactory
    .parseString("pekko.persistence.r2dbc.batched-journal.max-batch-time = 1s")
    .withFallback(R2dbcBatchJournalSpec.config)

  final case class StoredRow(pid: String, seqNr: Long, event: String, dbTimestamp: Instant)
}

class R2dbcBatchJournalFailureIsolationSpec
    extends ScalaTestWithActorTestKit(R2dbcBatchJournalFailureIsolationSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing
    with BatchedJournalDialectGate {

  override def typedSystem: ActorSystem[?] = system

  private implicit val journalPayloadCodec: PayloadCodec = journalSettings.journalPayloadCodec
  private val serialization = SerializationExtension(system)
  private lazy val journal = persistenceExt.journalFor("pekko.persistence.r2dbc.batched-journal")
  import R2dbcBatchJournalFailureIsolationSpec.StoredRow

  private def sendWrite(pid: String, seqNr: Long, event: String, replyTo: ActorRef[Any]): Unit =
    journal ! WriteMessages(
      Seq(AtomicWrite(PersistentRepr(event, seqNr, pid))),
      replyTo.toClassic,
      actorInstanceId = 1)

  private def expectSuccess(probe: TestProbe[Any], pid: String): Unit = {
    probe.expectMessage(10.seconds, WriteMessagesSuccessful)
    probe.expectMessageType[WriteMessageSuccess](10.seconds).persistent.persistenceId shouldBe pid
  }

  private def storedRows(): IndexedSeq[StoredRow] =
    r2dbcExecutor
      .select[StoredRow]("test")(
        connection =>
          connection.createStatement(
            s"select persistence_id, seq_nr, event_ser_id, event_ser_manifest, event_payload, db_timestamp " +
            s"from ${journalSettings.journalTableWithSchema}"),
        row => {
          val event = serialization
            .deserialize(
              row.getPayload("event_payload"),
              row.get[Integer]("event_ser_id", classOf[Integer]),
              row.get("event_ser_manifest", classOf[String]))
            .get
            .asInstanceOf[String]
          StoredRow(
            row.get("persistence_id", classOf[String]),
            row.get[java.lang.Long]("seq_nr", classOf[java.lang.Long]).longValue(),
            event,
            row.get("db_timestamp", classOf[Instant]))
        })
      .futureValue

  "R2dbcBatchJournal failure isolation" should {

    "fail only the persistence id violating the unique constraint when batched with other writes" in {
      val entityType = nextEntityType()
      val pidA = nextPid(entityType)
      val pidB = nextPid(entityType)
      val pidC = nextPid(entityType)

      val probeA = createTestProbe[Any]()
      val probeB = createTestProbe[Any]()
      val probeC = createTestProbe[Any]()

      // seed pidA seqNr 1 so that the duplicate write below violates PRIMARY KEY(persistence_id, seq_nr)
      sendWrite(pidA, 1L, "a1", probeA.ref)
      expectSuccess(probeA, pidA)

      // these three writes arrive within the 1 second batch window and are flushed as one batch,
      // the duplicate for pidA makes the batch fail with a unique constraint violation,
      // bisection retries the halves so that only pidA fails
      sendWrite(pidA, 1L, "a1-duplicate", probeA.ref)
      sendWrite(pidB, 1L, "b1", probeB.ref)
      sendWrite(pidC, 1L, "c1", probeC.ref)

      val failed = probeA.expectMessageType[WriteMessagesFailed](10.seconds)
      failed.cause shouldBe a[R2dbcDataIntegrityViolationException]
      val failure = probeA.expectMessageType[WriteMessageFailure](10.seconds)
      failure.cause shouldBe a[R2dbcDataIntegrityViolationException]
      failure.message.persistenceId shouldBe pidA
      failure.message.sequenceNr shouldBe 1L

      expectSuccess(probeB, pidB)
      expectSuccess(probeC, pidC)

      val rows = storedRows()
      rows.filter(_.pid == pidA).map(r => (r.seqNr, r.event)) shouldBe Vector((1L, "a1"))
      rows.filter(_.pid == pidB).map(r => (r.seqNr, r.event)) shouldBe Vector((1L, "b1"))
      rows.filter(_.pid == pidC).map(r => (r.seqNr, r.event)) shouldBe Vector((1L, "c1"))
    }

  }

}
