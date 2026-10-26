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

package org.apache.pekko.persistence.r2dbc.query

import scala.concurrent.duration._

import org.apache.pekko
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.query.NoOffset
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.query.typed.EventEnvelope
import pekko.persistence.r2dbc.TestActors
import pekko.persistence.r2dbc.TestActors.Persister.PersistWithAck
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.persistence.typed.PersistenceId
import pekko.stream.scaladsl.Sink
import pekko.stream.testkit.scaladsl.TestSink
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object EventsBySliceStartingFromSnapshotSmallBufferSpec {
  // small buffer-size so that a full page of events is before the snapshots
  def config: Config =
    ConfigFactory.load(
      ConfigFactory
        .parseString("""
    pekko.persistence.r2dbc.query.start-from-snapshot.enabled = true
    pekko.persistence.r2dbc.query.buffer-size = 10
    pekko.persistence.r2dbc.query.behind-current-time = 500 millis
    pekko.persistence.r2dbc.journal.publish-events = off
    """)
        .withFallback(TestConfig.backtrackingDisabledConfig)
        .withFallback(TestConfig.unresolvedConfig))
}

class EventsBySliceStartingFromSnapshotSmallBufferSpec
    extends ScalaTestWithActorTestKit(EventsBySliceStartingFromSnapshotSmallBufferSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[?] = system

  private val query = PersistenceQuery(testKit.system).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)

  private val bufferSize = 10
  private val numberOfEvents = 33
  private val snapshotSeqNr = 30

  private def expectedSnapshotEvent(prefix: String, seqNr: Long): String =
    (1L to seqNr).map(i => s"$prefix-$i").mkString("|") + "-snap"

  // more than buffer-size events before the snapshot of entity B, all filtered by the snapshot
  private def setup(): String = {
    val entityType = nextEntityType()
    val probe = createTestProbe[Done]()
    val snapshotAckProbe = createTestProbe[Long]()

    val persisterA = spawn(
      TestActors.Persister.withSnapshotAck(PersistenceId.ofUniqueId(nextPid(entityType)), Set.empty,
        snapshotAckProbe.ref))
    persisterA ! PersistWithAck("a-1-snap", probe.ref)
    snapshotAckProbe.expectMessage(1L)
    probe.expectMessage(Done)

    val persisterB = spawn(
      TestActors.Persister.withSnapshotAck(PersistenceId.ofUniqueId(nextPid(entityType)), Set.empty,
        snapshotAckProbe.ref))
    for (i <- 1 to numberOfEvents) {
      if (i == snapshotSeqNr) {
        persisterB ! PersistWithAck(s"b-$i-snap", probe.ref)
        snapshotAckProbe.expectMessage(i.toLong)
      } else
        persisterB ! PersistWithAck(s"b-$i", probe.ref)
      probe.expectMessage(Done)
    }

    entityType
  }

  private val expectedEvents =
    Seq(expectedSnapshotEvent("a", 1), expectedSnapshotEvent("b", snapshotSeqNr)) ++
    ((snapshotSeqNr + 1) to numberOfEvents).map(i => s"b-$i")

  "eventsBySlicesStartingFromSnapshots with a small buffer-size" should {
    "use the configured buffer-size" in {
      system.settings.config.getInt("pekko.persistence.r2dbc.query.buffer-size") shouldBe bufferSize
    }

    "emit the events after more than buffer-size filtered events in the current query" in {
      val entityType = setup()
      query
        .currentEventsBySlicesStartingFromSnapshots[String, String](
          entityType,
          0,
          persistenceExt.numberOfSlices - 1,
          NoOffset,
          snap => snap)
        .map(_.event)
        .runWith(Sink.seq)
        .futureValue shouldBe expectedEvents
    }

    "emit the events after more than buffer-size filtered events in the live query" in {
      val entityType = setup()
      val result = query
        .eventsBySlicesStartingFromSnapshots[String, String](
          entityType,
          0,
          persistenceExt.numberOfSlices - 1,
          NoOffset,
          snap => snap)
        .runWith(TestSink[EventEnvelope[String]]()(system.classicSystem))
        .request(expectedEvents.size + 1)

      result.expectNextN(expectedEvents.size).map(_.event) shouldBe expectedEvents
      result.expectNoMessage(1.second)
      result.cancel()
    }
  }
}
