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
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.internal.EnvelopeOrigin
import pekko.persistence.r2dbc.internal.PubSub
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.stream.testkit.scaladsl.TestSink
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

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
    pekko.cluster.jmx.multi-mbeans-in-same-jvm = on
    """)
        .withFallback(EventsBySlicePubSubSpec.config))
}

class EventsBySlicePubSubClusterSpec
    extends ScalaTestWithActorTestKit(EventsBySlicePubSubClusterSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[?] = system

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(10.seconds)

  private val query = PersistenceQuery(testKit.system).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)

  // writes the events, and runs no projection
  private val writingNode = ActorTestKit(system.name, EventsBySlicePubSubClusterSpec.config)

  override protected def afterAll(): Unit = {
    writingNode.shutdownTestKit()
    super.afterAll()
  }

  "EventsBySlices pub-sub in a cluster" should {
    "deliver the first event written on a node without a projection" in {
      val address = Cluster(system).selfMember.address
      Cluster(system).manager ! Join(address)
      Cluster(writingNode.system).manager ! Join(address)
      eventually {
        Cluster(system).state.members.count(_.status == MemberStatus.Up) shouldBe 2
      }

      val entityType = nextEntityType()
      val persistenceId = nextPid(entityType)
      val slice = query.sliceForPersistenceId(persistenceId)
      // the query reads no events (behind-current-time), so they can only come through pub-sub
      val result = query.eventsBySlices[String](entityType, slice, slice, NoOffset).runWith(TestSink()).request(10)

      // the writing node learns about the subscription, but has no topic of its own yet
      val topicKey = ServiceKey[TopicImpl.Command[EventEnvelope[String]]](PubSub(system).topicName(entityType, slice))
      val listingProbe = writingNode.createTestProbe[Receptionist.Listing]()
      eventually {
        writingNode.system.receptionist ! Receptionist.Find(topicKey, listingProbe.ref)
        listingProbe.receiveMessage().serviceInstances(topicKey) should have size 1
      }

      val persister = writingNode.spawn(TestActors.Persister(persistenceId))
      val probe = writingNode.createTestProbe[Done]()
      persister ! PersistWithAck("e-1", probe.ref)
      probe.expectMessage(Done)

      val env = result.expectNext()
      env.event shouldBe "e-1"
      env.source shouldBe EnvelopeOrigin.SourcePubSub
    }
  }
}
