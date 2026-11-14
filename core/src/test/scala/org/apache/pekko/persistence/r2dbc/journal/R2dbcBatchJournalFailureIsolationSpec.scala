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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import io.r2dbc.spi.R2dbcBadGrammarException
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import io.r2dbc.spi.R2dbcNonTransientException
import io.r2dbc.spi.R2dbcNonTransientResourceException
import io.r2dbc.spi.R2dbcPermissionDeniedException
import io.r2dbc.spi.R2dbcTimeoutException
import io.r2dbc.spi.R2dbcTransientResourceException

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

import org.scalatest.wordspec.AnyWordSpecLike

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

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
  private val scheduler = system.classicSystem.scheduler
  private implicit val ec: ExecutionContext = system.executionContext
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

  private def executeDdl(sql: String): Unit =
    r2dbcExecutor.executeDdl("test")(_.createStatement(sql)).futureValue

  "R2dbcBatchJournal failure isolation" should {

    "retry the whole batch with new timestamps after a serialization failure" in {
      journalSettings.dialect match {
        case Dialect.Postgres | Dialect.Yugabyte =>
        case _                                   => pending // the conflict is injected with a PL/pgSQL trigger
      }

      val entityType = nextEntityType()
      val pidA = nextPid(entityType)
      val pidB = nextPid(entityType)
      val pidC = nextPid(entityType)

      val probeA = createTestProbe[Any]()
      val probeB = createTestProbe[Any]()
      val probeC = createTestProbe[Any]()

      val table = journalSettings.journalTableWithSchema
      val name = "batch_retry_conflict"
      // nextval isn't rolled back with the failed transaction, so only the first insert of pidB fails
      executeDdl(s"drop trigger if exists $name on $table")
      executeDdl(s"drop sequence if exists ${name}_seq")
      executeDdl(s"create sequence ${name}_seq")
      executeDdl(
        s"create or replace function ${name}_fn() returns trigger as '" +
        s"begin if new.persistence_id = ''$pidB'' and nextval(''${name}_seq'') < 2 then " +
        "raise exception ''injected conflict'' using errcode = ''serialization_failure''; end if; " +
        "return new; end;' language plpgsql")
      executeDdl(s"create trigger $name before insert on $table for each row execute procedure ${name}_fn()")

      // the first attempt is stamped right after this log event
      val flushedAt = new AtomicLong
      val flushLogged = LoggingTestKit.custom { event =>
        val matches = event.message.startsWith("flushing [3] write requests")
        if (matches) flushedAt.set(event.timeStamp)
        matches
      }

      try {
        flushLogged.expect {
          LoggingTestKit.info("retry [1] of [3] write requests after a transient conflict").expect {
            journal ! batchedJournalWrite(pidA, 1L, "a1", probeA.ref)
            journal ! batchedJournalWrite(pidB, 1L, "b1", probeB.ref)
            journal ! batchedJournalWrite(pidC, 1L, "c1", probeC.ref)

            expectSuccess(probeA, pidA)
            expectSuccess(probeB, pidB)
            expectSuccess(probeC, pidC)
          }
        }
      } finally {
        executeDdl(s"drop trigger if exists $name on $table")
        executeDdl(s"drop function if exists ${name}_fn()")
        executeDdl(s"drop sequence if exists ${name}_seq")
      }

      // the retry ran as a whole, not as bisected halves, and each row is stored once
      val rows = storedRows().filter(r => Set(pidA, pidB, pidC).contains(r.pid))
      rows.map(r => (r.pid, r.seqNr, r.event)).toSet shouldBe Set((pidA, 1L, "a1"), (pidB, 1L, "b1"), (pidC, 1L, "c1"))
      rows.size shouldBe 3
      // the retry, delayed by at least the 50ms backoff, re-stamped the batch with later timestamps
      val retryBackoff = 50.millis
      rows.map(_.dbTimestamp.toEpochMilli).min should be >= (flushedAt.get + retryBackoff.toMillis - 5)
    }

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
      // r2dbc-mysql reports some data exceptions without a SQLSTATE, for example 1406 from a batched insert
      R2dbcBatchJournal.isNonTransientStatementError(
        new R2dbcNonTransientResourceException("Data too long for column", null, 1406)) shouldBe true
      R2dbcBatchJournal.isNonTransientStatementError(
        new R2dbcNonTransientResourceException("Invalid JSON text", null, 3140)) shouldBe true

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

    "classify only deadlocks and serialization failures as transient conflicts" in {
      // MySQL and MariaDB deadlock, with and without a SQLSTATE
      R2dbcBatchJournal.isTransientConflictError(
        new R2dbcTransientResourceException("Deadlock found", "40001", 1213)) shouldBe true
      R2dbcBatchJournal.isTransientConflictError(
        new R2dbcTransientResourceException("Deadlock found", null, 1213)) shouldBe true
      // PostgreSQL serialization failure and deadlock
      R2dbcBatchJournal.isTransientConflictError(
        new R2dbcTransientResourceException("could not serialize access", "40001", 0)) shouldBe true
      R2dbcBatchJournal.isTransientConflictError(
        new R2dbcTransientResourceException("deadlock detected", "40P01", 0)) shouldBe true

      // lock-wait timeouts arrive after the full lock timeout and would delay all queued writes, not retried
      R2dbcBatchJournal.isTransientConflictError(
        new R2dbcTimeoutException("Lock wait timeout exceeded", "HY000", 1205)) shouldBe false
      R2dbcBatchJournal.isTransientConflictError(
        new R2dbcTimeoutException("Lock wait timeout exceeded", null, 1205)) shouldBe false
      R2dbcBatchJournal.isTransientConflictError(
        new R2dbcTimeoutException("Timeout trying to lock table", "HYT00", 50200)) shouldBe false
      R2dbcBatchJournal.isTransientConflictError(
        new R2dbcTransientResourceException("canceling statement due to lock timeout", "55P03", 0)) shouldBe false

      // statement timeouts, integrity violations, connection and other errors are not retried
      R2dbcBatchJournal.isTransientConflictError(
        new R2dbcTimeoutException("canceling statement due to statement timeout", "57014", 0)) shouldBe false
      R2dbcBatchJournal.isTransientConflictError(
        new R2dbcDataIntegrityViolationException("duplicate key", "23505", 0)) shouldBe false
      R2dbcBatchJournal.isTransientConflictError(
        new R2dbcNonTransientResourceException("Connection unexpectedly closed")) shouldBe false
      R2dbcBatchJournal.isTransientConflictError(
        new R2dbcBadGrammarException("Table doesn't exist", "42S02", 1146)) shouldBe false
      R2dbcBatchJournal.isTransientConflictError(new IllegalStateException("not a database error")) shouldBe false
    }

    "retry a transient conflict until the attempt succeeds" in {
      val attempts = new AtomicInteger
      val retries = new ConcurrentLinkedQueue[Int]
      val result = R2dbcBatchJournal.retryTransientConflicts(2, 1.millis, scheduler)((_, retry) => retries.add(retry)) {
        () =>
          if (attempts.incrementAndGet() <= 2) Future.failed(new R2dbcTransientResourceException("Deadlock", "40001"))
          else Future.successful("written")
      }
      result.futureValue shouldBe "written"
      attempts.get shouldBe 3
      retries.asScala.toList shouldBe List(1, 2)
    }

    "fail with the transient conflict after the retries" in {
      val attempts = new AtomicInteger
      val conflict = new R2dbcTransientResourceException("Deadlock", "40001")
      val result = R2dbcBatchJournal.retryTransientConflicts(1, 1.millis, scheduler)((_, _) => ()) { () =>
        attempts.incrementAndGet()
        Future.failed[String](conflict)
      }
      result.failed.futureValue shouldBe conflict
      attempts.get shouldBe 2
    }

    "not retry other errors" in {
      val attempts = new AtomicInteger
      val violation = new R2dbcDataIntegrityViolationException("duplicate key", "23505", 0)
      val result = R2dbcBatchJournal.retryTransientConflicts(2, 1.millis, scheduler)((_, _) => ()) { () =>
        attempts.incrementAndGet()
        Future.failed[String](violation)
      }
      result.failed.futureValue shouldBe violation
      attempts.get shouldBe 1
    }

  }

}
