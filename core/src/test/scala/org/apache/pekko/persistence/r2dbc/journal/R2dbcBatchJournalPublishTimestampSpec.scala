/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.journal

import java.time.Instant
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorRef
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.internal.pubsub.TopicImpl
import pekko.actor.typed.pubsub.Topic
import pekko.actor.typed.scaladsl.adapter._
import pekko.persistence.AtomicWrite
import pekko.persistence.JournalProtocol.WriteMessageSuccess
import pekko.persistence.JournalProtocol.WriteMessages
import pekko.persistence.JournalProtocol.WriteMessagesSuccessful
import pekko.persistence.PersistentRepr
import pekko.persistence.query.TimestampOffset
import pekko.persistence.query.typed.EventEnvelope
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.internal.EnvelopeOrigin
import pekko.persistence.r2dbc.internal.PubSub
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object R2dbcBatchJournalPublishTimestampSpec {

  // long batch window so that the staggered writes below are guaranteed to be
  // coalesced into one batch flush
  val config: Config = ConfigFactory
    .parseString("pekko.persistence.r2dbc.batched-journal.max-batch-time = 1s")
    .withFallback(R2dbcBatchJournalSpec.config)
}

class R2dbcBatchJournalPublishTimestampSpec
    extends ScalaTestWithActorTestKit(R2dbcBatchJournalPublishTimestampSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing
    with BatchedJournalDialectGate {

  override def typedSystem: ActorSystem[?] = system

  private implicit val ec: ExecutionContext = system.executionContext

  private lazy val journal = persistenceExt.journalFor("pekko.persistence.r2dbc.batched-journal")

  private def writeMessages(pid: String, seqNr: Long, event: String, replyTo: ActorRef[Any]): WriteMessages =
    WriteMessages(
      immutable.Seq(AtomicWrite(PersistentRepr(event, seqNr, pid))),
      replyTo.toClassic,
      actorInstanceId = 1)

  private def storedTimestamps(): Map[String, Instant] =
    r2dbcExecutor
      .select[(String, Instant)]("test")(
        connection =>
          connection.createStatement(
            s"select persistence_id, db_timestamp from ${journalSettings.journalTableWithSchema}"),
        row => row.get("persistence_id", classOf[String]) -> row.get("db_timestamp", classOf[Instant]))
      .futureValue
      .toMap

  "R2dbcBatchJournal publish" should {

    "publish each write of a coalesced batch with its own stored db timestamp" in {
      val entityType = nextEntityType()
      val pids = (1 to 3).map(_ => nextPid(entityType))

      val envelopeProbe = createTestProbe[EventEnvelope[String]]()
      val topics = pids
        .map(pid => PubSub(system).eventTopic[String](entityType, persistenceExt.sliceForPersistenceId(pid)))
        .toSet
      topics.foreach(_ ! Topic.Subscribe(envelopeProbe.ref))

      // wait until the subscriptions are established
      val statsProbe = createTestProbe[TopicImpl.TopicStats]()
      topics.foreach { topic =>
        eventually {
          topic ! TopicImpl.GetTopicStats(statsProbe.ref)
          statsProbe.receiveMessage().localSubscriberCount shouldBe 1
        }
      }

      // stagger the writes slightly so that each gets a distinct application timestamp,
      // they still all fall inside the 1 second batch window and are coalesced into one flush
      val probes = pids.map(_ => createTestProbe[Any]())
      pids.zip(probes).zipWithIndex.foreach {
        case ((pid, probe), i) =>
          system.scheduler.scheduleOnce((i * 20).millis,
            () => journal ! writeMessages(pid, 1L, s"e-$i", probe.ref))
      }

      pids.zip(probes).foreach {
        case (pid, probe) =>
          probe.expectMessage(10.seconds, WriteMessagesSuccessful)
          probe.expectMessageType[WriteMessageSuccess](10.seconds).persistent.persistenceId shouldBe pid
      }

      val stored = storedTimestamps()
      stored.keySet shouldBe pids.toSet
      // guard the test premise: coalesced writes must have distinct stored timestamps,
      // otherwise a wrong shared publish timestamp could not be detected
      stored.values.toSet.size shouldBe 3

      val envelopes = envelopeProbe.receiveMessages(3, 10.seconds)
      envelopes.map(_.persistenceId).toSet shouldBe pids.toSet
      envelopes.foreach { env =>
        withClue(s"pid [${env.persistenceId}]: ") {
          env.source shouldBe EnvelopeOrigin.SourcePubSub
          env.sequenceNr shouldBe 1L
          env.event shouldBe s"e-${pids.indexOf(env.persistenceId)}"
          env.offset.asInstanceOf[TimestampOffset].timestamp shouldBe stored(env.persistenceId)
        }
      }
    }

  }

}
