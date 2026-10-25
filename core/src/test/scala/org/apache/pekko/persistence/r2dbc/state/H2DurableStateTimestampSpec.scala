/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.state

import java.time.Instant

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.query.NoOffset
import pekko.persistence.query.UpdatedDurableState
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.state.scaladsl.R2dbcDurableStateStore
import pekko.persistence.state.DurableStateStoreRegistry
import pekko.stream.scaladsl.Sink
import org.scalatest.Outcome
import org.scalatest.Pending
import org.scalatest.wordspec.AnyWordSpecLike

class H2DurableStateTimestampSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with TestDbLifecycle
    with TestData
    with AnyWordSpecLike
    with LogCapturing {

  private val isH2 = system.settings.config.getString("pekko.persistence.r2dbc.dialect") == "h2"

  override def withFixture(test: NoArgTest): Outcome =
    if (isH2) super.withFixture(test)
    else Pending

  override protected def beforeAll(): Unit =
    if (isH2) super.beforeAll()

  override def typedSystem: ActorSystem[?] = system

  private lazy val store = DurableStateStoreRegistry(testKit.system)
    .durableStateStoreFor[R2dbcDurableStateStore[String]](R2dbcDurableStateStore.Identifier)

  private def dbTimestamp(persistenceId: String): Instant =
    r2dbcExecutor
      .selectOne("select db_timestamp")(
        _.createStatement("SELECT db_timestamp FROM durable_state WHERE persistence_id = $1").bind(0, persistenceId),
        _.get(0, classOf[Instant]))
      .futureValue
      .get

  "The H2 durable state store" should {

    // the JVM clock can return the same value for a while (about 0.5 ms on Windows)
    "store a strictly increasing db_timestamp for consecutive revisions" in {
      val entityType = nextEntityType()
      val persistenceId = nextPid(entityType)
      val timestamps = (1 to 50).map { revision =>
        store.upsertObject(persistenceId, revision, s"s-$revision", "").futureValue
        dbTimestamp(persistenceId)
      }
      timestamps.sliding(2).foreach { case Seq(a, b) => a should be < b }
    }

    "find the latest state with a query right after the write" in {
      val entityType = nextEntityType()
      val persistenceId = nextPid(entityType)
      val slice = store.sliceForPersistenceId(persistenceId)
      (1 to 50).foreach { revision =>
        store.upsertObject(persistenceId, revision, s"s-$revision", "").futureValue
        val changes = store
          .currentChangesBySlices(entityType, slice, slice, NoOffset)
          .runWith(Sink.seq)
          .futureValue
        changes.collect { case u: UpdatedDurableState[?] => u.revision } shouldBe Seq(revision)
      }
    }
  }
}
