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

import org.apache.pekko
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.ActorTestKit
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.internal.pubsub.TopicImpl
import pekko.actor.typed.receptionist.Receptionist
import pekko.actor.typed.receptionist.ServiceKey
import pekko.cluster.MemberStatus
import pekko.cluster.typed.Cluster
import pekko.cluster.typed.Join
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
import pekko.stream.testkit.scaladsl.TestSink

import org.scalatest.wordspec.AnyWordSpecLike

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object EventsBySlicePubSubClusterSpec {
  def config: Config =
    ConfigFactory.load(
      ConfigFactory
        .parseString("""
    pekko.actor.provider = cluster
    pekko.remote.artery.canonical {
      hostname = "127.0.0.1"
      port = 0
    }
    pekko.cluster.jmx.enabled = off

    pekko.persistence.r2dbc {
      journal.publish-events = on

      # no events from database query, only via pub-sub
      behind-current-time = 5 minutes
    }
    """)
        .withFallback(TestConfig.backtrackingDisabledConfig.withFallback(TestConfig.unresolvedConfig)))
}

/**
 * Two nodes in one JVM: the query runs on the node of the test kit, the events are written on the other node.
 */
class EventsBySlicePubSubClusterSpec
    extends ScalaTestWithActorTestKit(EventsBySlicePubSubClusterSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[?] = system

  // node without a query, same actor system name to be able to join the cluster
  private val writerTestKit = ActorTestKit(system.name, EventsBySlicePubSubClusterSpec.config)

  private val query = PersistenceQuery(system).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)

  override protected def afterAll(): Unit = {
    writerTestKit.shutdownTestKit()
    super.afterAll()
  }

  "EventsBySlices pub-sub in a cluster" should {

    "form a cluster" in {
      val cluster = Cluster(system)
      cluster.manager ! Join(cluster.selfMember.address)
      Cluster(writerTestKit.system).manager ! Join(cluster.selfMember.address)

      eventually {
        cluster.state.members.toList.map(_.status) shouldBe List(MemberStatus.Up, MemberStatus.Up)
        Cluster(writerTestKit.system).state.members.toList.map(_.status) shouldBe List(
          MemberStatus.Up,
          MemberStatus.Up)
      }
    }

    "publish the first event written on a node without a query to the query on another node" in {
      val entityType = nextEntityType()
      val persistenceId = nextPid(entityType)
      val slice = query.sliceForPersistenceId(persistenceId)

      val result =
        query
          .eventsBySlices[String](entityType, slice, slice, NoOffset)
          .runWith(TestSink[EventEnvelope[String]]()(system.classicSystem))
          .request(10)

      val topic = PubSub(system).eventTopic[String](entityType, slice)
      val topicStatsProbe = createTestProbe[TopicImpl.TopicStats]()
      eventually {
        topic ! TopicImpl.GetTopicStats(topicStatsProbe.ref)
        topicStatsProbe.receiveMessage().localSubscriberCount shouldBe 1
      }

      // The receptionist of the writer node knows the topic instance of the query node before the writer node
      // creates its own topic instance, which it does when it publishes the first event.
      val topicServiceKey = ServiceKey[TopicImpl.Command[EventEnvelope[String]]](topic.path.name)
      val listingProbe = writerTestKit.createTestProbe[Receptionist.Listing]()
      eventually {
        writerTestKit.system.receptionist ! Receptionist.Find(topicServiceKey, listingProbe.ref)
        // the writer node sees the topic instance with the address of the query node
        listingProbe.receiveMessage().serviceInstances(topicServiceKey).map(_.path.toStringWithoutAddress) shouldBe
        Set(topic.path.toStringWithoutAddress)
      }

      val persister = writerTestKit.spawn(TestActors.Persister(persistenceId))
      val ackProbe = writerTestKit.createTestProbe[Done]()
      persister ! PersistWithAck("e-1", ackProbe.ref)
      ackProbe.expectMessage(Done)

      val first = result.expectNext()
      first.event shouldBe "e-1"
      first.source shouldBe EnvelopeOrigin.SourcePubSub

      persister ! PersistWithAck("e-2", ackProbe.ref)
      ackProbe.expectMessage(Done)

      val second = result.expectNext()
      second.event shouldBe "e-2"
      second.source shouldBe EnvelopeOrigin.SourcePubSub

      result.cancel()
    }
  }
}
