/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.query

import org.apache.pekko
import pekko.Done
import pekko.NotUsed
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.query.TimestampOffset
import pekko.persistence.query.{ EventEnvelope => ClassicEventEnvelope }
import pekko.persistence.r2dbc.TestActors
import pekko.persistence.r2dbc.TestActors.Persister
import pekko.persistence.r2dbc.TestActors.Persister.PersistWithAck
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.persistence.typed.PersistenceId
import pekko.persistence.typed.internal.ReplicatedEventMetadata
import pekko.stream.scaladsl.Source
import pekko.stream.testkit.TestSubscriber
import pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.wordspec.AnyWordSpecLike

object EventsByPersistenceIdSpec {
  sealed trait QueryType
  case object Live extends QueryType
  case object Current extends QueryType
}

class EventsByPersistenceIdSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {
  import EventsByPersistenceIdSpec._

  override def typedSystem: ActorSystem[_] = system

  private val query = PersistenceQuery(testKit.system).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)

  List[QueryType](Live, Current).foreach { queryType =>
    def doQuery(pid: String, from: Long, to: Long): Source[ClassicEventEnvelope, NotUsed] =
      queryType match {
        case Live =>
          query.eventsByPersistenceId(pid, from, to)
        case Current =>
          query.currentEventsByPersistenceId(pid, from, to)
      }

    def assertFinished(probe: TestSubscriber.Probe[_], liveShouldFinish: Boolean = false): Unit =
      queryType match {
        case Live if !liveShouldFinish =>
          probe.expectNoMessage()
          probe.cancel()
        case _ =>
          probe.expectComplete()
      }

    s"$queryType eventsByPersistenceId" should {
      "populates timestamp offset" in {
        val pid = nextPid()
        val persister = testKit.spawn(Persister(pid))
        val probe = testKit.createTestProbe[Done]()
        persister ! Persister.PersistWithAck("e-1", probe.ref)
        probe.expectMessage(Done)

        val sub = doQuery(pid, 0, Long.MaxValue)
          .runWith(TestSink())
          .request(1)

        sub.expectNextPF {
          case ClassicEventEnvelope(TimestampOffset(_, _, seen), `pid`, 1, "e-1") if seen == Map(pid -> 1) =>
        }

        assertFinished(sub)
      }

      "return all events then complete" in {
        val pid = nextPid()
        val persister = testKit.spawn(Persister(pid))
        val probe = testKit.createTestProbe[Done]()
        val events = (1 to 20).map { i =>
          val payload = s"e-$i"
          persister ! PersistWithAck(payload, probe.ref)
          probe.expectMessage(Done)
          payload
        }

        val sub = doQuery(pid, 0, Long.MaxValue)
          .map(_.event)
          .runWith(TestSink())

        sub
          .request(events.size + 1)
          .expectNextN(events.size)

        assertFinished(sub)
      }

      "only return sequence nrs requested" in {
        val pid = nextPid()
        val persister = testKit.spawn(Persister(pid))
        val probe = testKit.createTestProbe[Done]()
        val events = (1 to 20).map { i =>
          val payload = s"e-$i"
          persister ! PersistWithAck(payload, probe.ref)
          probe.expectMessage(Done)
          payload
        }

        val sub = doQuery(pid, 0, 5)
          .map(_.event)
          .runWith(TestSink())

        sub
          .request(events.size + 1)
          .expectNextN(events.take(5))

        assertFinished(sub, liveShouldFinish = true)
      }

      "allow querying for a single event" in {
        val pid = nextPid()
        val persister = testKit.spawn(Persister(pid))
        val probe = testKit.createTestProbe[Done]()

        (1 to 3).map { i =>
          val payload = s"e-$i"
          persister ! PersistWithAck(payload, probe.ref)
          probe.expectMessage(Done)
          payload
        }

        val sub = doQuery(pid, 2, 2)
          .map(_.event)
          .runWith(TestSink())

        val event = sub
          .request(2)
          .expectNext()
        event should ===("e-2")

        assertFinished(sub, liveShouldFinish = true)
      }

      "include metadata" in {
        val probe = testKit.createTestProbe[Done]()
        val entityType = nextEntityType()
        val entityId = "entity-1"

        val persister = testKit.spawn(TestActors.replicatedEventSourcedPersister(entityType, entityId))
        persister ! Persister.PersistWithAck("e-1", probe.ref)
        probe.expectMessage(Done)
        persister ! Persister.PersistWithAck("e-2", probe.ref)
        probe.expectMessage(Done)

        val sub = doQuery(PersistenceId(entityType, entityId).id, 0, Long.MaxValue)
          .runWith(TestSink())
          .request(10)

        val env1 = sub.expectNext()
        env1.event shouldBe "e-1"
        val meta1 = env1.eventMetadata.get.asInstanceOf[ReplicatedEventMetadata]
        meta1.originReplica.id shouldBe "dc-1"
        meta1.originSequenceNr shouldBe 1L

        val env2 = sub.expectNext()
        env2.event shouldBe "e-2"
        val meta2 = env2.eventMetadata.get.asInstanceOf[ReplicatedEventMetadata]
        meta2.originReplica.id shouldBe "dc-1"
        meta2.originSequenceNr shouldBe 2L

        assertFinished(sub)
      }
    }
  }

  "Live query" should {
    "pick up new events" in {
      val pid = nextPid()
      val persister = testKit.spawn(Persister(pid))
      val probe = testKit.createTestProbe[Done]()
      val sub = query
        .eventsByPersistenceId(pid, 0, Long.MaxValue)
        .map(_.event)
        .runWith(TestSink())
      val events = (1 to 20).map { i =>
        val payload = s"e-$i"
        persister ! PersistWithAck(payload, probe.ref)
        probe.expectMessage(Done)
        payload
      }

      sub.request(21)
      sub.expectNextN(events)

      val events2 = (21 to 40).map { i =>
        val payload = s"e-$i"
        // make the live query can deliver an element it picks up so it can end its query and give up the sesion
        sub.request(1)
        persister ! PersistWithAck(payload, probe.ref)
        probe.expectMessage(Done)
        payload
      }
      sub.request(1)
      sub.expectNextN(events2)

      sub.expectNoMessage()
      sub.cancel()
    }
  }
}
