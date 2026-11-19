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

package org.apache.pekko.persistence.r2dbc.journal

import com.typesafe.config.Config
import org.apache.pekko
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.SerializedEvent
import pekko.persistence.r2dbc.TestActors.Persister
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.typed.PersistenceId
import pekko.serialization.SerializationExtension
import pekko.serialization.Serializers
import org.scalatest.wordspec.AnyWordSpecLike

abstract class AbstractPersistSerializedEventSpec(config: Config)
    extends ScalaTestWithActorTestKit(config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[?] = system

  case class Row(seqNr: Long, serializerId: Int, manifest: String)

  private def serializedEvent(event: AnyRef): SerializedEvent = {
    val serializer = SerializationExtension(system).findSerializerFor(event)
    new SerializedEvent(serializer.toBinary(event), serializer.identifier, Serializers.manifestFor(serializer, event))
  }

  "Persist SerializedEvent" should {

    "be stored with given serialization information" in {
      val doneProbe = createTestProbe[Done]()

      val entityType = nextEntityType()
      val persistenceId = PersistenceId.ofUniqueId(nextPid(entityType))
      val ref = spawn(Persister(persistenceId, Set.empty))

      // String serialization has no manifest
      val serializedEvent1 = serializedEvent("e1")
      serializedEvent1.serializerManifest shouldBe ""

      // Option serialization has manifest
      val serializedEvent2 = serializedEvent(Some("e2"))
      serializedEvent2.serializerManifest should not be ""

      ref ! Persister.Persist(serializedEvent1)
      ref ! Persister.PersistWithAck(serializedEvent2, doneProbe.ref)
      doneProbe.expectMessage(Done)
      testKit.stop(ref)

      val ref2 = spawn(Persister(persistenceId, Set.empty))
      val replyProbe = createTestProbe[String]()
      ref2 ! Persister.GetState(replyProbe.ref)
      replyProbe.expectMessage("e1|Some(e2)")

      val rows =
        r2dbcExecutor
          .select[Row]("test")(
            connection =>
              connection.createStatement(
                s"select * from ${journalSettings.journalTableWithSchema} where persistence_id = '${persistenceId.id}'"),
            row =>
              Row(
                seqNr = row.get[java.lang.Long]("seq_nr", classOf[java.lang.Long]),
                serializerId = row.get[java.lang.Integer]("event_ser_id", classOf[java.lang.Integer]),
                manifest = row.get("event_ser_manifest", classOf[String])))
          .futureValue

      rows.sortBy(_.seqNr) shouldBe List(
        Row(1L, serializedEvent1.serializerId, serializedEvent1.serializerManifest),
        Row(2L, serializedEvent2.serializerId, serializedEvent2.serializerManifest))
    }

  }
}

class PersistSerializedEventSpec extends AbstractPersistSerializedEventSpec(TestConfig.config)

class PersistSerializedEventBatchJournalSpec
    extends AbstractPersistSerializedEventSpec(R2dbcBatchJournalSpec.config)
