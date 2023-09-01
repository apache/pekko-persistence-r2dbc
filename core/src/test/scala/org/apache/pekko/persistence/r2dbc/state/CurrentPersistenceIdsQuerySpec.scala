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

package org.apache.pekko.persistence.r2dbc.state

import scala.concurrent.duration._

import org.apache.pekko
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.TestActors
import pekko.persistence.r2dbc.TestActors.DurableStatePersister
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.state.scaladsl.R2dbcDurableStateStore
import pekko.persistence.state.DurableStateStoreRegistry
import pekko.persistence.typed.PersistenceId
import pekko.stream.scaladsl.Sink
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

class CurrentPersistenceIdsQuerySpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString("""
        pekko.persistence.r2dbc.query.persistence-ids.buffer-size = 20
        """)
        .withFallback(TestConfig.config))
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[_] = system

  private val store = DurableStateStoreRegistry(testKit.system)
    .durableStateStoreFor[R2dbcDurableStateStore[String]](R2dbcDurableStateStore.Identifier)

  private val zeros = "0000"
  private val entityType = nextEntityType()
  private val numberOfPids = 100
  private val pids =
    (1 to numberOfPids).map(n => PersistenceId(entityType, "p" + zeros.drop(n.toString.length) + n))

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    val probe = createTestProbe[Done]()
    pids.foreach { pid =>
      val persister = spawn(TestActors.DurableStatePersister(pid))
      persister ! DurableStatePersister.PersistWithAck("s-1", probe.ref)
      persister ! DurableStatePersister.Stop(probe.ref)
    }

    probe.receiveMessages(numberOfPids * 2, 30.seconds) // ack + stop done
  }

  "Durable State persistenceIds" should {
    "retrieve all ids" in {
      val result = store.currentPersistenceIds().runWith(Sink.seq).futureValue
      result shouldBe pids.map(_.id)
    }

    "retrieve ids afterId" in {
      val result = store.currentPersistenceIds(afterId = Some(pids(9).id), limit = 7).runWith(Sink.seq).futureValue
      result shouldBe pids.slice(10, 17).map(_.id)
    }

  }

}
