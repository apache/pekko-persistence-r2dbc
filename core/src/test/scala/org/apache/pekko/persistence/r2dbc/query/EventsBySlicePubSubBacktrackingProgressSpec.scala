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
import pekko.actor.typed.internal.pubsub.TopicImpl
import pekko.persistence.query.NoOffset
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.query.typed.EventEnvelope
import pekko.persistence.r2dbc.TestActors
import pekko.persistence.r2dbc.TestActors.Persister.PersistWithAck
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.internal.EnvelopeOrigin
import pekko.persistence.r2dbc.internal.PubSub
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.stream.testkit.TestSubscriber
import pekko.stream.testkit.scaladsl.TestSink
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object EventsBySlicePubSubBacktrackingProgressSpec {
  private val config = ConfigFactory
    .parseString("""
    pekko.persistence.r2dbc {
      journal.publish-events = on
      query {
        refresh-interval = 500 millis
        behind-current-time = 1 second
        backtracking {
          enabled = on
          # longer than a backtracking round and its behind-current-time, so that the latest progress is within it
          window = 6 seconds
          behind-current-time = 1 second
        }
      }
    }
    """)
    .withFallback(TestConfig.config)
}

class EventsBySlicePubSubBacktrackingProgressSpec
    extends ScalaTestWithActorTestKit(EventsBySlicePubSubBacktrackingProgressSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[?] = system

  private val query = PersistenceQuery(testKit.system).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)

  "eventsBySlices with pub-sub and backtracking" should {

    "accept pub-sub events after an idle period longer than the backtracking window" in {
      val entityType = nextEntityType()
      val persistenceId = nextPid(entityType)
      val slice = query.sliceForPersistenceId(persistenceId)
      val persister = spawn(TestActors.Persister(persistenceId))
      val probe = createTestProbe[Done]()

      val result: TestSubscriber.Probe[EventEnvelope[String]] =
        query
          .eventsBySlices[String](entityType, slice, slice, NoOffset)
          .runWith(TestSink[EventEnvelope[String]]()(system.classicSystem))
          .request(100)

      val topicStatsProbe = createTestProbe[TopicImpl.TopicStats]()
      eventually {
        PubSub(typedSystem).eventTopic[String](entityType, slice) ! TopicImpl.GetTopicStats(topicStatsProbe.ref)
        topicStatsProbe.receiveMessage().localSubscriberCount shouldBe 1
      }

      persister ! PersistWithAck("e-1", probe.ref)
      probe.expectMessage(Done)
      result.expectNext(10.seconds).event shouldBe "e-1"

      // Idle for longer than the backtracking window. Only copies of e-1 arrive, from the query and backtracking.
      result.receiveWithin(8.seconds).foreach(_.persistenceId shouldBe persistenceId)

      persister ! PersistWithAck("e-2", probe.ref)
      probe.expectMessage(Done)
      // Without backtracking progress the pub-sub event would be dropped as too far ahead of the latest backtracking
      // event (e-1), and e-2 would arrive from the query.
      val env2 = result.expectNext(1500.millis)
      env2.event shouldBe "e-2"
      env2.source shouldBe EnvelopeOrigin.SourcePubSub

      result.cancel()
    }
  }
}
