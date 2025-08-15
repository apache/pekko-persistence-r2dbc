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

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit }
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.{ TestConfig, TestData, TestDbLifecycle }
import pekko.persistence.r2dbc.state.scaladsl.{ DurableStateExceptionSupport, R2dbcDurableStateStore }
import pekko.persistence.state.DurableStateStoreRegistry
import pekko.persistence.state.scaladsl.GetObjectResult
import pekko.persistence.typed.PersistenceId
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class DurableStateStoreSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[_] = system

  private val store = DurableStateStoreRegistry(testKit.system)
    .durableStateStoreFor[R2dbcDurableStateStore[String]](R2dbcDurableStateStore.Identifier)

  private val unusedTag = "n/a"

  "The R2DBC durable state store" should {
    "save and retrieve a value" in {
      val entityType = nextEntityType()
      val persistenceId = PersistenceId(entityType, "my-persistenceId").id
      val value = "Genuinely Collaborative"

      store.upsertObject(persistenceId, 1L, value, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(value), 1L))
    }

    "produce None when fetching a non-existing key" in {
      val entityType = nextEntityType()
      val key = PersistenceId(entityType, "nonexistent-id").id
      store.getObject(key).futureValue should be(GetObjectResult(None, 0L))
    }

    "update a value" in {
      val entityType = nextEntityType()
      val persistenceId = PersistenceId(entityType, "id-to-be-updated").id
      val value = "Genuinely Collaborative"
      store.upsertObject(persistenceId, 1L, value, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(value), 1L))

      val updatedValue = "Open to Feedback"
      store.upsertObject(persistenceId, 2L, updatedValue, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(updatedValue), 2L))
    }

    "detect and reject concurrent inserts" in {
      val entityType = nextEntityType()
      val persistenceId = PersistenceId(entityType, "id-to-be-inserted-concurrently")
      val value = "Genuinely Collaborative"
      store.upsertObject(persistenceId.id, revision = 1L, value, entityType).futureValue
      store.getObject(persistenceId.id).futureValue should be(GetObjectResult(Some(value), 1L))

      val updatedValue = "Open to Feedback"
      val failure =
        store.upsertObject(persistenceId.id, revision = 1L, updatedValue, entityType).failed.futureValue
      failure.getMessage should include(
        s"Insert failed: durable state for persistence id [${persistenceId.id}] already exists")
    }

    "detect and reject concurrent updates" in {
      if (!stateSettings.durableStateAssertSingleWriter)
        pending

      val entityType = nextEntityType()
      val persistenceId = PersistenceId(entityType, "id-to-be-updated-concurrently")
      val value = "Genuinely Collaborative"
      store.upsertObject(persistenceId.id, revision = 1L, value, entityType).futureValue
      store.getObject(persistenceId.id).futureValue should be(GetObjectResult(Some(value), 1L))

      val updatedValue = "Open to Feedback"
      store.upsertObject(persistenceId.id, revision = 2L, updatedValue, entityType).futureValue
      store.getObject(persistenceId.id).futureValue should be(GetObjectResult(Some(updatedValue), 2L))

      // simulate an update by a different node that didn't see the first one:
      val updatedValue2 = "Genuine and Sincere in all Communications"
      val failure =
        store.upsertObject(persistenceId.id, revision = 2L, updatedValue2, entityType).failed.futureValue
      failure.getMessage should include(
        s"Update failed: durable state for persistence id [${persistenceId.id}] could not be updated to revision [2]")
    }

    "support deletions" in {
      val entityType = nextEntityType()
      val persistenceId = PersistenceId(entityType, "to-be-added-and-removed").id
      val value = "Genuinely Collaborative"
      store.upsertObject(persistenceId, 1L, value, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(value), 1L))
      store.deleteObject(persistenceId).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(None, 0L))
    }

    "support deletions with revision" in {
      val entityType = nextEntityType()
      val persistenceId = PersistenceId(entityType, "to-be-added-and-removed").id
      val value = "Genuinely Collaborative"
      store.upsertObject(persistenceId, 1L, value, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(value), 1L))
      store.deleteObject(persistenceId, 1L).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(None, 0L))
    }

    "fail deleteObject call when revision is unknown" in {
      val entityType = nextEntityType()
      val persistenceId = PersistenceId(entityType, "to-be-added-and-removed").id
      val value = "Genuinely Collaborative"
      store.upsertObject(persistenceId, 1L, value, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(value), 1L))
      if (pekko.Version.current.startsWith("1.0")) {
        store.deleteObject(persistenceId, 2L).futureValue
      } else {
        val ex = intercept[Exception] {
          Await.result(store.deleteObject(persistenceId, 2L), 20.seconds)
        }
        ex.getClass.getName shouldEqual DurableStateExceptionSupport.DeleteRevisionExceptionClass
      }
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(value), 1L))
    }

  }

}
