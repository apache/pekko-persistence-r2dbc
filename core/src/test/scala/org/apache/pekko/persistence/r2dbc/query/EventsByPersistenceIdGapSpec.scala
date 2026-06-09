/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.persistence.r2dbc.query

import scala.concurrent.duration._

import org.apache.pekko
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.r2dbc.TestActors.Persister
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.cleanup.scaladsl.EventSourcedCleanup
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.stream.scaladsl.Sink
import pekko.stream.testkit.scaladsl.TestSink
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Tests covering sequence-number gap handling in eventsByPersistenceId and
 * currentEventsByPersistenceId queries for PostgreSQL.
 *
 * Gaps arise when journal rows are hard-deleted (physical removal) or
 * soft-deleted (the `deleted` flag is set to `true`, e.g. via the delete-marker
 * left by deleteEventsTo / deleteMessagesTo). Both cases must be transparent to
 * the consumer: every non-deleted event in the requested range must be emitted
 * exactly once, in order.
 *
 * The test config uses `buffer-size = 1` to force the ContinuousQuery pagination
 * to issue multiple database round trips and so exercise the gap-crossing logic.
 *
 * Inspired by pekko-persistence-jdbc#517
 * (MessagesWithBatchDatabaseContractTest).
 */
object EventsByPersistenceIdGapSpec {
  // A small buffer-size forces multiple round trips through the ContinuousQuery
  // pagination, making it possible to observe gaps that span a batch boundary.
  val config = ConfigFactory
    .parseString("pekko.persistence.r2dbc.buffer-size = 1")
    .withFallback(TestConfig.config)

  val dialect: String = config.getString("pekko.persistence.r2dbc.dialect")
}

class EventsByPersistenceIdGapSpec
    extends ScalaTestWithActorTestKit(EventsByPersistenceIdGapSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[_] = system

  private val query =
    PersistenceQuery(system).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Persist `count` events for `pid` via a Persister actor and return seqNrs 1..count. */
  private def persistEvents(pid: String, count: Int): Unit = {
    val probe = createTestProbe[Done]()
    val persister = spawn(Persister(pid))
    (1 to count).foreach { i =>
      persister ! Persister.PersistWithAck(s"e-$i", probe.ref)
      probe.expectMessage(10.seconds, Done)
    }
    testKit.stop(persister)
  }

  /**
   * Hard-delete specific rows from the journal table by directly issuing a
   * DELETE statement.  This simulates the scenario from pekko-persistence-jdbc
   * issue #516 where messages are removed without leaving a delete marker.
   *
   * Uses PostgreSQL numbered parameter syntax ($1, $2, …) as required by the
   * r2dbc-postgresql driver.
   */
  private def hardDeleteSeqNrs(pid: String, seqNrs: Long*): Unit = {
    val table = journalSettings.journalTableWithSchema
    val sql = if (EventsByPersistenceIdGapSpec.dialect == "mysql") {
      // MySQL R2DBC uses ? as parameter markers, so we can use the same SQL for
      // any number of seqNrs.
      val inList = seqNrs.map(_ => "?").mkString(", ")
      s"DELETE FROM $table WHERE persistence_id = ? AND seq_nr IN ($inList)"
    } else {
      // PostgreSQL R2DBC uses numbered parameters: $1, $2, $3, … so we need to
      // generate the SQL with the correct number of parameters.
      val inList = seqNrs.zipWithIndex.map { case (_, i) => s"$$${i + 2}" }.mkString(", ")
      s"DELETE FROM $table WHERE persistence_id = $$1 AND seq_nr IN ($inList)"
    }
    r2dbcExecutor
      .updateOne("hard-delete seqNrs") { connection =>
        val stmt = connection.createStatement(sql).bind(0, pid)
        seqNrs.zipWithIndex.foreach { case (seqNr, idx) => stmt.bind(idx + 1, seqNr) }
        stmt
      }
      .futureValue
    ()
  }

  /**
   * Collect the sequence numbers emitted by `currentEventsByPersistenceId` for
   * the given pid and range, waiting for the stream to complete.
   */
  private def currentSeqNrs(pid: String, from: Long = 1L, to: Long = Long.MaxValue): Seq[Long] =
    query
      .currentEventsByPersistenceId(pid, from, to)
      .map(_.sequenceNr)
      .runWith(Sink.seq)
      .futureValue

  // ---------------------------------------------------------------------------
  // currentEventsByPersistenceId
  // ---------------------------------------------------------------------------

  "currentEventsByPersistenceId" should {

    "emit all events when a hard-deleted gap is wider than the buffer size" in {
      val pid = nextPid()
      persistEvents(pid, 4)
      hardDeleteSeqNrs(pid, 2L, 3L)

      val seqNrs = currentSeqNrs(pid, to = 4)
      seqNrs shouldBe Seq(1L, 4L)
    }

    "emit all events across multiple hard-deleted gaps" in {
      val pid = nextPid()
      persistEvents(pid, 8)
      // Two separate gaps: [2,3] and [6,7]
      hardDeleteSeqNrs(pid, 2L, 3L, 6L, 7L)

      val seqNrs = currentSeqNrs(pid, to = 8)
      seqNrs shouldBe Seq(1L, 4L, 5L, 8L)
    }

    "emit all events when a gap mixes hard-deleted and soft-deleted (delete-marker) rows" in {
      // Persist events 1..5.
      // deleteEventsTo(3) will hard-delete 1..3 and leave a delete marker
      // (deleted=true) at seqNr 3.  Hard-delete seqNr 4 separately.
      // Only seqNr 5 survives and must be returned.
      val pid = nextPid()
      persistEvents(pid, 5)

      val cleanup = new EventSourcedCleanup(system)
      cleanup.deleteEventsTo(pid, 3L).futureValue

      hardDeleteSeqNrs(pid, 4L)

      val seqNrs = currentSeqNrs(pid, to = 5)
      seqNrs shouldBe Seq(5L)
    }

    "complete with no events when every event is covered by a delete marker" in {
      // deleteEventsTo(3) hard-deletes 1..3 and inserts a delete marker at 3.
      // Since we query [1,3] and the marker has deleted=true, no event must
      // be emitted.
      val pid = nextPid()
      persistEvents(pid, 3)

      val cleanup = new EventSourcedCleanup(system)
      cleanup.deleteEventsTo(pid, 3L).futureValue

      val seqNrs = currentSeqNrs(pid, to = 3)
      seqNrs shouldBe empty
    }

    // The journal's deleteEventsTo hard-deletes everything up to the target
    // sequence number and leaves a single delete marker (deleted=true) at that
    // number.  Events beyond the target must still be discoverable – this is
    // the "prefix purge" scenario from pekko-persistence-jdbc#516.
    "emit the remaining events after a prefix purge via deleteEventsTo" in {
      val pid = nextPid()
      persistEvents(pid, 4)

      val cleanup = new EventSourcedCleanup(system)
      cleanup.deleteEventsTo(pid, 3L).futureValue

      val seqNrs = currentSeqNrs(pid, to = 4)
      seqNrs shouldBe Seq(4L)
    }

    "emit remaining events after deleteEventsTo leaves a gap wider than the buffer size" in {
      val pid = nextPid()
      persistEvents(pid, 6)

      val cleanup = new EventSourcedCleanup(system)
      cleanup.deleteEventsTo(pid, 4L).futureValue

      val seqNrs = currentSeqNrs(pid, to = 6)
      seqNrs shouldBe Seq(5L, 6L)
    }
  }

  // ---------------------------------------------------------------------------
  // eventsByPersistenceId (live / unbounded)
  // ---------------------------------------------------------------------------

  "eventsByPersistenceId (live query)" should {

    "emit all events when a hard-deleted gap is wider than the buffer size" in {
      val pid = nextPid()
      persistEvents(pid, 4)
      hardDeleteSeqNrs(pid, 2L, 3L)

      val sub = query
        .eventsByPersistenceId(pid, 1L, 4L)
        .map(_.sequenceNr)
        .runWith(TestSink[Long]())

      sub.request(4)
      sub.expectNextN(Seq(1L, 4L))
      sub.expectComplete()
    }

    "emit the remaining events after a prefix purge via deleteEventsTo (live query)" in {
      val pid = nextPid()
      persistEvents(pid, 4)

      val cleanup = new EventSourcedCleanup(system)
      cleanup.deleteEventsTo(pid, 3L).futureValue

      val sub = query
        .eventsByPersistenceId(pid, 1L, 4L)
        .map(_.sequenceNr)
        .runWith(TestSink[Long]())

      sub.request(4)
      sub.expectNext(4L)
      sub.expectComplete()
    }
  }
}
