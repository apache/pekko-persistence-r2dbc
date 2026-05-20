/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.query

import org.apache.pekko
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.internal.pubsub.TopicImpl
import pekko.persistence.query.NoOffset
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.query.typed.EventEnvelope
import pekko.persistence.r2dbc.TestActors
import pekko.persistence.r2dbc.TestActors.Persister.PersistWithAck
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.internal.PubSub
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.stream.testkit.TestSubscriber
import pekko.stream.testkit.scaladsl.TestSink
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

object EventsBySlicePubSubOverflowSpec {
  def config: Config =
    ConfigFactory.load(
      ConfigFactory
        .parseString("""
    pekko.persistence.r2dbc {
      journal.publish-events = on
      query {
        # Small buffer so that the pub-sub overflow strategy is forced to kick in
        # when the consumer is not pulling (no downstream demand).
        buffer-size = 3
        # Enable deduplication so that events delivered via both pub-sub and
        # the DB recovery source are not emitted twice.
        deduplicate-capacity = 100
      }
    }
    pekko.actor.testkit.typed.filter-leeway = 20.seconds
    """)
        .withFallback(TestConfig.backtrackingDisabledConfig.withFallback(TestConfig.unresolvedConfig))
    )
}

class EventsBySlicePubSubOverflowSpec
    extends ScalaTestWithActorTestKit(EventsBySlicePubSubOverflowSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[_] = system

  private val query =
    PersistenceQuery(testKit.system).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)

  "EventsBySlices pub-sub" should {

    "not lose events when the pub-sub buffer overflows and the DB source recovers them" in {
      val entityType = nextEntityType()
      val persistenceId = nextPid(entityType)
      val slice = query.sliceForPersistenceId(persistenceId)
      val persister = spawn(TestActors.Persister(persistenceId))
      val probe = createTestProbe[Done]()
      val sinkProbe = TestSink[EventEnvelope[String]]()(system.classicSystem)

      // Start the query but do NOT request any elements (no downstream demand).
      // The pub-sub actorRef source pushes events into its buffer regardless of
      // downstream demand. With buffer-size=3 and 20 incoming events the buffer
      // overflows: OverflowStrategy.dropHead evicts the oldest buffered event on
      // each overflow, so only the 3 most-recent events survive in the buffer.
      val result: TestSubscriber.Probe[EventEnvelope[String]] =
        query
          .eventsBySlices[String](entityType, slice, slice, NoOffset)
          .runWith(sinkProbe)

      // Wait for the pub-sub subscription to be established before persisting.
      val topicStatsProbe = createTestProbe[TopicImpl.TopicStats]()
      eventually {
        PubSub(typedSystem).eventTopic[String](entityType, slice) ! TopicImpl.GetTopicStats(topicStatsProbe.ref)
        topicStatsProbe.receiveMessage().localSubscriberCount shouldBe 1
      }

      // Persist many more events than the buffer-size (3).
      // All events are written to the DB and published to pub-sub.
      // After the 3rd event the pub-sub buffer is full: each subsequent push
      // evicts the oldest buffered event (dropHead), so the buffer will hold
      // only the 3 most-recently published events when we start consuming.
      val eventCount = 20
      for (i <- 1 to eventCount) {
        persister ! PersistWithAck(s"e-$i", probe.ref)
        probe.expectMessage(Done)
      }

      // Now signal demand. The pub-sub source delivers the 3 events still in
      // its buffer. Events that overflowed are recovered by the DB source
      // (merged at lower priority). The deduplicate stage prevents duplicates
      // for the events received via both paths.
      result.request(eventCount.toLong)
      val received = (1 to eventCount).map(_ => result.expectNext(30.seconds))
      received.map(_.event).toSet shouldBe (1 to eventCount).map(i => s"e-$i").toSet

      result.cancel()
    }
  }
}
