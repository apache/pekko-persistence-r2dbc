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
import pekko.actor.testkit.typed.scaladsl.LoggingTestKit
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.testkit.typed.scaladsl.TestProbe
import pekko.actor.typed.ActorSystem
import pekko.persistence.JournalProtocol.WriteMessageFailure
import pekko.persistence.JournalProtocol.WriteMessageSuccess
import pekko.persistence.JournalProtocol.WriteMessagesFailed
import pekko.persistence.JournalProtocol.WriteMessagesSuccessful
import pekko.persistence.r2dbc.Dialect
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.internal.PayloadCodec
import pekko.persistence.r2dbc.internal.PayloadCodec.RichRow
import pekko.serialization.SerializationExtension
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.r2dbc.spi.R2dbcBadGrammarException
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import io.r2dbc.spi.R2dbcNonTransientException
import io.r2dbc.spi.R2dbcNonTransientResourceException
import io.r2dbc.spi.R2dbcPermissionDeniedException
import io.r2dbc.spi.R2dbcTransientResourceException
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
    with LogCapturing {

  override def typedSystem: ActorSystem[?] = system

  private implicit val journalPayloadCodec: PayloadCodec = journalSettings.journalPayloadCodec
  private val serialization = SerializationExtension(system)
  private lazy val journal = persistenceExt.journalFor("pekko.persistence.r2dbc.batched-journal")
  import R2dbcBatchJournalFailureIsolationSpec.StoredRow

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
      journal ! batchedJournalWrite(pidA, 1L, "a1", probeA.ref)
      expectSuccess(probeA, pidA)

      // these three writes arrive within the 1 second batch window and are flushed as one batch,
      // the duplicate for pidA makes the batch fail with a unique constraint violation,
      // bisection retries the halves so that only pidA fails
      LoggingTestKit.debug("flushing [3] write requests").expect {
        journal ! batchedJournalWrite(pidA, 1L, "a1-duplicate", probeA.ref)
        journal ! batchedJournalWrite(pidB, 1L, "b1", probeB.ref)
        journal ! batchedJournalWrite(pidC, 1L, "c1", probeC.ref)

        val failed = probeA.expectMessageType[WriteMessagesFailed](10.seconds)
        failed.cause shouldBe a[R2dbcDataIntegrityViolationException]
        val failure = probeA.expectMessageType[WriteMessageFailure](10.seconds)
        failure.cause shouldBe a[R2dbcDataIntegrityViolationException]
        failure.message.persistenceId shouldBe pidA
        failure.message.sequenceNr shouldBe 1L

        expectSuccess(probeB, pidB)
        expectSuccess(probeC, pidC)
      }

      val rows = storedRows()
      rows.filter(_.pid == pidA).map(r => (r.seqNr, r.event)) shouldBe Vector((1L, "a1"))
      rows.filter(_.pid == pidB).map(r => (r.seqNr, r.event)) shouldBe Vector((1L, "b1"))
      rows.filter(_.pid == pidC).map(r => (r.seqNr, r.event)) shouldBe Vector((1L, "c1"))
    }

    "fail only the persistence id with an oversized payload on dialects with a payload size limit" in {
      val entityType = nextEntityType()
      val pidA = nextPid(entityType)
      val pidB = nextPid(entityType)

      val probeA = createTestProbe[Any]()
      val probeB = createTestProbe[Any]()

      val oversized = "a" * 70000
      LoggingTestKit.debug("flushing [2] write requests").expect {
        journal ! batchedJournalWrite(pidA, 1L, oversized, probeA.ref)
        journal ! batchedJournalWrite(pidB, 1L, "b1", probeB.ref)

        if (journalSettings.dialect == Dialect.MySQL || journalSettings.dialect == Dialect.MariaDB) {
          val failed = probeA.expectMessageType[WriteMessagesFailed](10.seconds)
          failed.cause shouldBe a[R2dbcNonTransientException]
          val failure = probeA.expectMessageType[WriteMessageFailure](10.seconds)
          failure.cause shouldBe a[R2dbcNonTransientException]
          failure.message.persistenceId shouldBe pidA
          failure.message.sequenceNr shouldBe 1L

          expectSuccess(probeB, pidB)
        } else {
          expectSuccess(probeA, pidA)
          expectSuccess(probeB, pidB)
        }
      }

      if (journalSettings.dialect == Dialect.MySQL || journalSettings.dialect == Dialect.MariaDB) {
        storedRows().filter(r => r.pid == pidA || r.pid == pidB).map(_.pid).toSet shouldBe Set(pidB)
      } else {
        storedRows().filter(r => r.pid == pidA || r.pid == pidB).map(_.pid).toSet shouldBe Set(pidA, pidB)
      }
    }

    "classify only statement data and integrity errors as retryable by bisection" in {
      R2dbcBatchJournal.isNonTransientStatementError(
        new R2dbcDataIntegrityViolationException("duplicate key", "23505", 0)) shouldBe true
      R2dbcBatchJournal.isNonTransientStatementError(
        new R2dbcBadGrammarException("Data too long for column", "22001", 1406)) shouldBe true
      R2dbcBatchJournal.isNonTransientStatementError(
        new R2dbcNonTransientResourceException("Data too long for column", "22001", 1406)) shouldBe true
      R2dbcBatchJournal.isNonTransientStatementError(
        new R2dbcNonTransientResourceException("Invalid JSON text", "22032", 3140)) shouldBe true

      // schema errors, server-wide errors, connection errors, permission errors, and transient errors
      // must fail the whole batch without bisection
      R2dbcBatchJournal.isNonTransientStatementError(
        new R2dbcBadGrammarException("Table doesn't exist", "42S02", 1146)) shouldBe false
      R2dbcBatchJournal.isNonTransientStatementError(
        new R2dbcNonTransientResourceException("Running with --read-only", "HY000", 1290)) shouldBe false
      R2dbcBatchJournal.isNonTransientStatementError(
        new R2dbcNonTransientResourceException("Running with --read-only", null, 1290)) shouldBe false
      R2dbcBatchJournal.isNonTransientStatementError(
        new R2dbcNonTransientResourceException("Connection unexpectedly closed")) shouldBe false
      R2dbcBatchJournal.isNonTransientStatementError(
        new R2dbcNonTransientResourceException("Connection error", "08000", 9000)) shouldBe false
      R2dbcBatchJournal.isNonTransientStatementError(
        new R2dbcPermissionDeniedException("Access denied", "28000", 1045)) shouldBe false
      R2dbcBatchJournal.isNonTransientStatementError(
        new R2dbcTransientResourceException("Deadlock found", "40001", 1213)) shouldBe false
    }

  }

}
