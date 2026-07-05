/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2022 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.cleanup.scaladsl

import org.apache.pekko
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.LoggingTestKit
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.Behaviors
import pekko.persistence.r2dbc.TestActors.Persister
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.event.Level

object EventSourcedCleanupSpec {
  val config = ConfigFactory
    .parseString(s"""
    pekko.loglevel = DEBUG
    pekko.persistence.r2dbc.cleanup {
      log-progress-every = 2
      events-journal-delete-batch-size = 10
    }
  """)
    .withFallback(TestConfig.config)
}

class EventSourcedCleanupSpec
    extends ScalaTestWithActorTestKit(EventSourcedCleanupSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[?] = system

  "EventSourcedCleanup" must {
    "delete events for one persistenceId" in {
      val ackProbe = createTestProbe[Done]()
      val stateProbe = createTestProbe[String]()
      val seqNrProbe = createTestProbe[Long]()
      val pid = nextPid()
      val p = spawn(Persister(pid))

      (1 to 10).foreach { n =>
        p ! Persister.PersistWithAck(n, ackProbe.ref)
        ackProbe.expectMessage(Done)
      }

      testKit.stop(p)

      val cleanup = new EventSourcedCleanup(system)
      cleanup.deleteAllEvents(pid, resetSequenceNumber = true).futureValue

      val p2 = spawn(Persister(pid))
      p2 ! Persister.GetState(stateProbe.ref)
      stateProbe.expectMessage("")
      p2 ! Persister.GetSeqNr(seqNrProbe.ref)
      seqNrProbe.expectMessage(0L)
    }

    "delete events for one persistenceId in batches" in {
      val ackProbe = createTestProbe[Done]()
      val stateProbe = createTestProbe[String]()
      val seqNrProbe = createTestProbe[Long]()
      val pid = nextPid()
      val p = spawn(Persister(pid))

      val maxSeqNumber = 47
      (1 to maxSeqNumber).foreach { n =>
        p ! Persister.PersistWithAck(n, ackProbe.ref)
        ackProbe.expectMessage(Done)
      }

      testKit.stop(p)

      val cleanup = new EventSourcedCleanup(system)

      var iteration = 0
      val batchSize = 10

      LoggingTestKit
        .info("Deleted")
        .withLogLevel(Level.DEBUG)
        .withOccurrences(5)
        .withCustom { event =>
          val from = (iteration * batchSize) + 1
          iteration = iteration + 1
          val to = Math.min(maxSeqNumber, from + batchSize - 1)
          val deleted = to - from + 1
          val expectedMsg = s"Deleted [$deleted] events for persistenceId [$pid], from seq num [$from] to [$to]"
          event.message == expectedMsg
        }
        .expect {
          cleanup.deleteAllEvents(pid, resetSequenceNumber = true).futureValue
        }

      val p2 = spawn(Persister(pid))
      p2 ! Persister.GetState(stateProbe.ref)
      stateProbe.expectMessage("")
      p2 ! Persister.GetSeqNr(seqNrProbe.ref)
      seqNrProbe.expectMessage(0L)
    }

    "delete events for one persistenceId, but keep seqNr" in {
      val ackProbe = createTestProbe[Done]()
      val stateProbe = createTestProbe[String]()
      val seqNrProbe = createTestProbe[Long]()
      val pid = nextPid()
      val p = spawn(Persister(pid))

      (1 to 10).foreach { n =>
        p ! Persister.PersistWithAck(n, ackProbe.ref)
        ackProbe.expectMessage(Done)
      }

      testKit.stop(p)

      val cleanup = new EventSourcedCleanup(system)
      cleanup.deleteAllEvents(pid, resetSequenceNumber = false).futureValue

      val p2 = spawn(Persister(pid))
      p2 ! Persister.GetState(stateProbe.ref)
      stateProbe.expectMessage("")
      p2 ! Persister.GetSeqNr(seqNrProbe.ref)
      seqNrProbe.expectMessage(10L)
    }

    "delete events for one persistenceId in batches, but keep seqNr" in {
      val ackProbe = createTestProbe[Done]()
      val stateProbe = createTestProbe[String]()
      val seqNrProbe = createTestProbe[Long]()
      val pid = nextPid()
      val p = spawn(Persister(pid))

      val maxSeqNumber = 47
      (1 to maxSeqNumber).foreach { n =>
        p ! Persister.PersistWithAck(n, ackProbe.ref)
        ackProbe.expectMessage(Done)
      }

      testKit.stop(p)

      val cleanup = new EventSourcedCleanup(system)

      var iteration = 0
      val batchSize = 10

      LoggingTestKit
        .info("Deleted")
        .withLogLevel(Level.DEBUG)
        .withOccurrences(5)
        .withCustom { event =>
          val from = (iteration * batchSize) + 1
          iteration = iteration + 1
          val to = Math.min(maxSeqNumber, from + batchSize - 1)
          val deleted = to - from + 1
          val expectedMsg = s"Deleted [$deleted] events for persistenceId [$pid], from seq num [$from] to [$to]"
          event.message == expectedMsg
        }
        .expect {
          cleanup.deleteAllEvents(pid, resetSequenceNumber = false).futureValue
        }

      val p2 = spawn(Persister(pid))
      p2 ! Persister.GetState(stateProbe.ref)
      stateProbe.expectMessage("")
      p2 ! Persister.GetSeqNr(seqNrProbe.ref)
      seqNrProbe.expectMessage(maxSeqNumber.toLong)
    }

    "delete some for one persistenceId" in {
      val ackProbe = createTestProbe[Done]()
      val stateProbe = createTestProbe[String]()
      val seqNrProbe = createTestProbe[Long]()
      val pid = nextPid()
      val p = spawn(Persister(pid))

      (1 to 8).foreach { n =>
        p ! Persister.PersistWithAck(n, ackProbe.ref)
        ackProbe.expectMessage(Done)
      }

      testKit.stop(p)

      val cleanup = new EventSourcedCleanup(system)
      cleanup.deleteEventsTo(pid, 5).futureValue

      val p2 = spawn(Persister(pid))
      p2 ! Persister.GetState(stateProbe.ref)
      stateProbe.expectMessage("6|7|8")
      p2 ! Persister.GetSeqNr(seqNrProbe.ref)
      seqNrProbe.expectMessage(8L)
    }

    "delete snapshots for one persistenceId" in {
      val ackProbe = createTestProbe[Done]()
      val stateProbe = createTestProbe[String]()
      val pid = nextPid()
      val p = spawn(Behaviors.setup[Persister.Command] { context =>
        Persister
          .eventSourcedBehavior(PersistenceId.ofUniqueId(pid), context)
          .snapshotWhen((_, event, _) => event.toString.contains("snap"))
      })

      (1 to 10).foreach { n =>
        p ! Persister.PersistWithAck(s"${if (n == 3) s"$n-snap" else n}", ackProbe.ref)
        ackProbe.expectMessage(Done)
      }

      testKit.stop(p)

      val cleanup = new EventSourcedCleanup(system)
      cleanup.deleteAllEvents(pid, resetSequenceNumber = false).futureValue

      val p2 = spawn(Persister(pid))
      p2 ! Persister.GetState(stateProbe.ref)
      stateProbe.expectMessage("1|2|3-snap")
      testKit.stop(p2)

      cleanup.deleteSnapshot(pid).futureValue

      val p3 = spawn(Persister(pid))
      p3 ! Persister.GetState(stateProbe.ref)
      stateProbe.expectMessage("")
    }

    "cleanup before snapshot" in {
      val ackProbe = createTestProbe[Done]()
      val stateProbe = createTestProbe[String]()
      val pid = nextPid()
      val p = spawn(Behaviors.setup[Persister.Command] { context =>
        Persister
          .eventSourcedBehavior(PersistenceId.ofUniqueId(pid), context)
          .snapshotWhen((_, event, _) => event.toString.contains("snap"))
      })

      (1 to 10).foreach { n =>
        p ! Persister.PersistWithAck(s"${if (n == 3) s"$n-snap" else n}", ackProbe.ref)
        ackProbe.expectMessage(Done)
      }

      testKit.stop(p)

      val cleanup = new EventSourcedCleanup(system)
      cleanup.cleanupBeforeSnapshot(pid).futureValue

      val p2 = spawn(Persister(pid))
      p2 ! Persister.GetState(stateProbe.ref)
      stateProbe.expectMessage("1|2|3-snap|4|5|6|7|8|9|10")
      testKit.stop(p2)

      cleanup.deleteSnapshot(pid).futureValue

      val p3 = spawn(Persister(pid))
      p3 ! Persister.GetState(stateProbe.ref)
      // from replaying remaining events
      stateProbe.expectMessage("4|5|6|7|8|9|10")
    }

    "delete all" in {
      val ackProbe = createTestProbe[Done]()
      val stateProbe = createTestProbe[String]()
      val seqNrProbe = createTestProbe[Long]()
      val pids = Vector(nextPid(), nextPid(), nextPid())
      val persisters =
        pids.map { pid =>
          spawn(Behaviors.setup[Persister.Command] { context =>
            Persister
              .eventSourcedBehavior(PersistenceId.ofUniqueId(pid), context)
              .snapshotWhen((_, event, _) => event.toString.contains("snap"))
          })
        }

      (1 to 10).foreach { n =>
        persisters.foreach { p =>
          p ! Persister.PersistWithAck(s"${if (n == 3) s"$n-snap" else n}", ackProbe.ref)
          ackProbe.expectMessage(Done)
        }
      }

      persisters.foreach(testKit.stop(_))

      val cleanup = new EventSourcedCleanup(system)
      cleanup.deleteAll(pids, resetSequenceNumber = true).futureValue

      val persisters2 = pids.map(pid => spawn(Persister(pid)))
      persisters2.foreach { p =>
        p ! Persister.GetState(stateProbe.ref)
        stateProbe.expectMessage("")
        p ! Persister.GetSeqNr(seqNrProbe.ref)
        seqNrProbe.expectMessage(0L)
      }
    }

    "cleanup all before snapshot" in {
      val ackProbe = createTestProbe[Done]()
      val stateProbe = createTestProbe[String]()
      val pids = Vector(nextPid(), nextPid(), nextPid())
      val persisters =
        pids.map { pid =>
          spawn(Behaviors.setup[Persister.Command] { context =>
            Persister
              .eventSourcedBehavior(PersistenceId.ofUniqueId(pid), context)
              .snapshotWhen((_, event, _) => event.toString.contains("snap"))
          })
        }

      (1 to 10).foreach { n =>
        persisters.foreach { p =>
          p ! Persister.PersistWithAck(s"${if (n == 3) s"$n-snap" else n}", ackProbe.ref)
          ackProbe.expectMessage(Done)
        }
      }

      persisters.foreach(testKit.stop(_))

      val cleanup = new EventSourcedCleanup(system)
      cleanup.cleanupBeforeSnapshot(pids).futureValue
      cleanup.deleteSnapshots(pids).futureValue

      val persisters2 = pids.map(pid => spawn(Persister(pid)))
      persisters2.foreach { p =>
        p ! Persister.GetState(stateProbe.ref)
        // from replaying remaining events
        stateProbe.expectMessage("4|5|6|7|8|9|10")
      }
    }
  }

}
