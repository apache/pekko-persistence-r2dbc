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
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.TestActors.DurableStatePersister
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object DurableStateCleanupSpec {
  val config = ConfigFactory
    .parseString(s"""
    pekko.loglevel = DEBUG
    pekko.persistence.r2dbc.cleanup {
      log-progress-every = 2
    }
  """)
    .withFallback(TestConfig.config)
}

class DurableStateCleanupSpec
    extends ScalaTestWithActorTestKit(DurableStateCleanupSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[_] = system

  "DurableStateCleanup" must {
    "delete state for one persistenceId" in {
      val ackProbe = createTestProbe[Done]()
      val stateProbe = createTestProbe[Any]()
      val revisionProbe = createTestProbe[Long]()
      val pid = nextPid()
      val p = spawn(DurableStatePersister(pid))

      p ! DurableStatePersister.PersistWithAck("a", ackProbe.ref)
      ackProbe.expectMessage(Done)

      testKit.stop(p)

      val cleanup = new DurableStateCleanup(system)
      cleanup.deleteState(pid, resetRevisionNumber = true).futureValue

      val p2 = spawn(DurableStatePersister(pid))
      p2 ! DurableStatePersister.GetState(stateProbe.ref)
      stateProbe.expectMessage("")
      p2 ! DurableStatePersister.GetRevision(revisionProbe.ref)
      revisionProbe.expectMessage(0L)
    }

    "delete events for one persistenceId, but keep seqNr" in {
      val ackProbe = createTestProbe[Done]()
      val stateProbe = createTestProbe[Any]()
      val revisionProbe = createTestProbe[Long]()
      val pid = nextPid()
      val p = spawn(DurableStatePersister(pid))

      p ! DurableStatePersister.PersistWithAck("a", ackProbe.ref)
      ackProbe.expectMessage(Done)
      p ! DurableStatePersister.PersistWithAck("b", ackProbe.ref)
      ackProbe.expectMessage(Done)

      testKit.stop(p)

      val cleanup = new DurableStateCleanup(system)
      cleanup.deleteState(pid, resetRevisionNumber = false).futureValue

      val p2 = spawn(DurableStatePersister(pid))
      p2 ! DurableStatePersister.GetState(stateProbe.ref)
      stateProbe.expectMessage("")
      p2 ! DurableStatePersister.GetRevision(revisionProbe.ref)
      revisionProbe.expectMessage(3L)
    }

    "delete all" in {
      val ackProbe = createTestProbe[Done]()
      val stateProbe = createTestProbe[Any]()
      val seqNrProbe = createTestProbe[Long]()
      val pids = Vector(nextPid(), nextPid(), nextPid())
      val persisters = pids.map(pid => spawn(DurableStatePersister(pid)))

      (1 to 3).foreach { n =>
        persisters.foreach { p =>
          p ! DurableStatePersister.PersistWithAck(s"$n", ackProbe.ref)
          ackProbe.expectMessage(Done)
        }
      }

      persisters.foreach(testKit.stop(_))

      val cleanup = new DurableStateCleanup(system)
      cleanup.deleteStates(pids, resetRevisionNumber = true).futureValue

      val persisters2 = pids.map(pid => spawn(DurableStatePersister(pid)))
      persisters2.foreach { p =>
        p ! DurableStatePersister.GetState(stateProbe.ref)
        stateProbe.expectMessage("")
        p ! DurableStatePersister.GetRevision(seqNrProbe.ref)
        seqNrProbe.expectMessage(0L)
      }
    }

  }

}
