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

import scala.concurrent.Future
import scala.concurrent.duration._

import org.apache.pekko
import pekko.Done
import pekko.NotUsed
import pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.testkit.typed.scaladsl.TestProbe
import pekko.actor.typed.ActorSystem
import pekko.persistence.query.DurableStateChange
import pekko.persistence.query.NoOffset
import pekko.persistence.query.Offset
import pekko.persistence.query.TimestampOffset
import pekko.persistence.query.UpdatedDurableState
import pekko.persistence.r2dbc.TestActors
import pekko.persistence.r2dbc.TestActors.DurableStatePersister.Persist
import pekko.persistence.r2dbc.TestActors.DurableStatePersister.PersistWithAck
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.state.scaladsl.R2dbcDurableStateStore
import pekko.persistence.state.DurableStateStoreRegistry
import pekko.stream.KillSwitches
import pekko.stream.scaladsl.Sink
import pekko.stream.scaladsl.Source
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object DurableStateBySliceSpec {
  sealed trait QueryType
  case object Live extends QueryType
  case object Current extends QueryType

  def config: Config =
    TestConfig.backtrackingDisabledConfig
      .withFallback(ConfigFactory.parseString(s"""
    pekko.persistence.r2dbc-small-buffer = $${pekko.persistence.r2dbc}
    pekko.persistence.r2dbc-small-buffer.query {
      buffer-size = 3
    }
    """))
      .withFallback(TestConfig.config)
      .resolve()
}

class DurableStateBySliceSpec
    extends ScalaTestWithActorTestKit(DurableStateBySliceSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {
  import DurableStateBySliceSpec._

  override def typedSystem: ActorSystem[_] = system

  private val query = DurableStateStoreRegistry(testKit.system)
    .durableStateStoreFor[R2dbcDurableStateStore[String]](R2dbcDurableStateStore.Identifier)

  private class Setup {
    val entityType = nextEntityType()
    val persistenceId = nextPid(entityType)
    val slice = query.sliceForPersistenceId(persistenceId)
    val persister = spawn(TestActors.DurableStatePersister(persistenceId))
    val probe = createTestProbe[Done]()
    val updatedDurableStateProbe = createTestProbe[UpdatedDurableState[String]]()
    val killSwitch = KillSwitches.shared("test")
  }

  def fishForState(state: String, probe: TestProbe[UpdatedDurableState[String]]): Seq[UpdatedDurableState[String]] =
    probe.fishForMessage(probe.remainingOrDefault) { chg =>
      if (chg.value == state)
        FishingOutcomes.complete
      else
        FishingOutcomes.continueAndIgnore
    }

  List[QueryType](Current, Live).foreach { queryType =>
    def doQuery(
        entityType: String,
        minSlice: Int,
        maxSlice: Int,
        offset: Offset,
        queryImpl: R2dbcDurableStateStore[String] = query): Source[DurableStateChange[String], NotUsed] =
      queryType match {
        case Live =>
          queryImpl.changesBySlices(entityType, minSlice, maxSlice, offset)
        case Current =>
          queryImpl.currentChangesBySlices(entityType, minSlice, maxSlice, offset)
      }

    def assertFinished(probe: TestProbe[_], streamDone: Future[Done]): Unit =
      queryType match {
        case Live =>
          probe.expectNoMessage()
        case Current =>
          probe.expectNoMessage()
          streamDone.futureValue
      }

    s"$queryType changesBySlices" should {
      "return latest state for NoOffset" in new Setup {
        for (i <- 1 to 20) {
          persister ! PersistWithAck(s"s-$i", probe.ref)
          probe.expectMessage(10.seconds, Done)
        }

        val done =
          doQuery(entityType, slice, slice, NoOffset)
            .collect { case u: UpdatedDurableState[String] => u }
            .via(killSwitch.flow)
            .runWith(Sink.foreach(updatedDurableStateProbe.ref.tell))

        fishForState(s"s-20", updatedDurableStateProbe)
        assertFinished(updatedDurableStateProbe, done)
        killSwitch.shutdown()
      }

      "only return states after an offset" in new Setup {
        for (i <- 1 to 20) {
          persister ! PersistWithAck(s"s-$i", probe.ref)
          probe.expectMessage(Done)
        }

        val done =
          doQuery(entityType, slice, slice, NoOffset)
            .collect { case u: UpdatedDurableState[String] => u }
            .via(killSwitch.flow)
            .runWith(Sink.foreach(updatedDurableStateProbe.ref.tell))

        val result = fishForState(s"s-20", updatedDurableStateProbe).last

        val offset = result.offset

        for (i <- 21 to 30) {
          queryType match {
            case Live =>
              // don't wait for ack
              persister ! Persist(s"s-$i")
            case Current =>
              persister ! PersistWithAck(s"s-$i", probe.ref)
              probe.expectMessage(Done)
          }
        }

        val updatedDurableStateProbe2 = createTestProbe[UpdatedDurableState[String]]()

        val withOffsetDone =
          doQuery(entityType, slice, slice, offset)
            .collect { case u: UpdatedDurableState[String] => u }
            .via(killSwitch.flow)
            .runWith(Sink.foreach(updatedDurableStateProbe2.ref.tell))

        val result2 = fishForState(s"s-30", updatedDurableStateProbe2)

        result2.map(_.revision).min shouldBe >(result.revision)

        assertFinished(updatedDurableStateProbe2, withOffsetDone)
        killSwitch.shutdown()
      }
    }
  }

  // tests just relevant for current query
  "Current changesBySlices" should {
    "filter states with the same timestamp based on seen sequence nrs" in new Setup {
      persister ! PersistWithAck(s"s-1", probe.ref)
      probe.expectMessage(Done)
      val singleState: UpdatedDurableState[String] =
        query
          .currentChangesBySlices(entityType, slice, slice, NoOffset)
          .collect { case u: UpdatedDurableState[String] => u }
          .runWith(Sink.head)
          .futureValue
      val offset = singleState.offset.asInstanceOf[TimestampOffset]
      offset.seen shouldEqual Map(singleState.persistenceId -> singleState.revision)
      query
        .currentChangesBySlices(entityType, slice, slice, offset)
        .take(1)
        .runWith(Sink.headOption)
        .futureValue shouldEqual None
    }

    "not filter states with the same timestamp based on sequence nrs" in new Setup {
      persister ! PersistWithAck(s"s-1", probe.ref)
      probe.expectMessage(Done)
      val singleState: UpdatedDurableState[String] =
        query
          .currentChangesBySlices(entityType, slice, slice, NoOffset)
          .collect { case u: UpdatedDurableState[String] => u }
          .runWith(Sink.head)
          .futureValue
      val offset = singleState.offset.asInstanceOf[TimestampOffset]
      offset.seen shouldEqual Map(singleState.persistenceId -> singleState.revision)

      val offsetWithoutSeen = TimestampOffset(offset.timestamp, Map.empty)
      val singleState2 = query
        .currentChangesBySlices(entityType, slice, slice, offsetWithoutSeen)
        .collect { case u: UpdatedDurableState[String] => u }
        .runWith(Sink.headOption)
        .futureValue
      singleState2.get.value shouldBe "s-1"
    }

  }

  // tests just relevant for live query
  "Live changesBySlices" should {
    "find new changes" in new Setup {
      for (i <- 1 to 20) {
        persister ! PersistWithAck(s"s-$i", probe.ref)
        probe.expectMessage(Done)
      }
      val done =
        query
          .changesBySlices(entityType, slice, slice, NoOffset)
          .collect { case u: UpdatedDurableState[String] => u }
          .via(killSwitch.flow)
          .runWith(Sink.foreach(updatedDurableStateProbe.ref.tell))

      fishForState(s"s-20", updatedDurableStateProbe)

      for (i <- 21 to 40) {
        persister ! PersistWithAck(s"s-$i", probe.ref)
        probe.expectMessage(Done)
      }

      fishForState(s"s-40", updatedDurableStateProbe)
      killSwitch.shutdown()
    }

  }

}
