/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.journal

import scala.collection.immutable
import scala.concurrent.duration._
import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorRef
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.persistence.AtomicWrite
import pekko.persistence.JournalProtocol.WriteMessageFailure
import pekko.persistence.JournalProtocol.WriteMessageSuccess
import pekko.persistence.JournalProtocol.WriteMessages
import pekko.persistence.JournalProtocol.WriteMessagesFailed
import pekko.persistence.JournalProtocol.WriteMessagesSuccessful
import pekko.persistence.PersistentRepr
import pekko.persistence.r2dbc.ConnectionFactoryProvider
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.internal.R2dbcExecutor.PublisherOps
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object R2dbcBatchJournalBatchingSpec {

  // writes are held until max-batch-size is reached, the long batch window
  // guarantees that only the size trigger can complete the writes in the test
  val sizeFlushConfig: Config = ConfigFactory
    .parseString("""
      pekko.persistence.r2dbc.batched-journal {
        max-queue-size = 10
        max-batch-size = 3
        max-batch-time = 10s
      }""")
    .withFallback(R2dbcBatchJournalSpec.config)

  // a single write below max-batch-size is flushed when the batch window expires
  val timerFlushConfig: Config = ConfigFactory
    .parseString("pekko.persistence.r2dbc.batched-journal.max-batch-time = 500ms")
    .withFallback(R2dbcBatchJournalSpec.config)

  // the only pooled connection is held by the test so the flush started by the first
  // write stays in progress and the queue fills deterministically
  val queueLimitConfig: Config = ConfigFactory
    .parseString("""
      pekko.persistence.r2dbc {
        batched-journal {
          max-queue-size = 3
          max-batch-size = 1
          max-batch-time = 500ms
          use-connection-factory = "pekko.persistence.r2dbc.queue-limit-test-connection-factory"
        }
      }
      pekko.persistence.r2dbc.queue-limit-test-connection-factory = ${pekko.persistence.r2dbc.connection-factory} {
        initial-size = 1
        max-size = 1
        acquire-timeout = 30s
      }""")
    .withFallback(R2dbcBatchJournalSpec.config)
    .resolve()

  def writeMessages(pid: String, seqNr: Long, event: String, replyTo: ActorRef[Any]): WriteMessages =
    WriteMessages(
      immutable.Seq(AtomicWrite(PersistentRepr(event, seqNr, pid))),
      replyTo.toClassic,
      actorInstanceId = 1)
}

class R2dbcBatchJournalSizeFlushSpec
    extends ScalaTestWithActorTestKit(R2dbcBatchJournalBatchingSpec.sizeFlushConfig)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing
    with BatchedJournalDialectGate {
  import R2dbcBatchJournalBatchingSpec.writeMessages

  override def typedSystem: ActorSystem[?] = system

  private lazy val journal = persistenceExt.journalFor("pekko.persistence.r2dbc.batched-journal")

  "R2dbcBatchJournal size flush" should {

    "hold writes until max-batch-size is reached and then complete them all" in {
      val entityType = nextEntityType()
      val pids = (1 to 3).map(_ => nextPid(entityType))
      val probes = pids.map(_ => createTestProbe[Any]())

      // max-batch-time is 10s so the first two writes cannot complete on their own
      pids.take(2).zip(probes).foreach {
        case (pid, probe) =>
          journal ! writeMessages(pid, 1L, s"e-$pid", probe.ref)
      }
      probes.take(2).foreach(_.expectNoMessage(1.second))

      // the third write reaches max-batch-size = 3 and triggers the flush of the whole batch
      journal ! writeMessages(pids(2), 1L, s"e-${pids(2)}", probes(2).ref)

      pids.zip(probes).foreach {
        case (pid, probe) =>
          probe.expectMessage(5.seconds, WriteMessagesSuccessful)
          probe.expectMessageType[WriteMessageSuccess](5.seconds).persistent.persistenceId shouldBe pid
      }
    }

  }

}

class R2dbcBatchJournalTimerFlushSpec
    extends ScalaTestWithActorTestKit(R2dbcBatchJournalBatchingSpec.timerFlushConfig)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing
    with BatchedJournalDialectGate {
  import R2dbcBatchJournalBatchingSpec.writeMessages

  override def typedSystem: ActorSystem[?] = system

  private lazy val journal = persistenceExt.journalFor("pekko.persistence.r2dbc.batched-journal")

  "R2dbcBatchJournal timer flush" should {

    "flush a partial batch when max-batch-time expires" in {
      val entityType = nextEntityType()
      val pid = nextPid(entityType)
      val probe = createTestProbe[Any]()

      journal ! writeMessages(pid, 1L, s"e-$pid", probe.ref)

      // max-batch-size is 100 so the write cannot complete before the 500ms batch window expires
      probe.expectNoMessage(200.millis)
      probe.expectMessage(5.seconds, WriteMessagesSuccessful)
      probe.expectMessageType[WriteMessageSuccess](5.seconds).persistent.persistenceId shouldBe pid
    }

  }

}

class R2dbcBatchJournalQueueLimitSpec
    extends ScalaTestWithActorTestKit(R2dbcBatchJournalBatchingSpec.queueLimitConfig)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing
    with BatchedJournalDialectGate {
  import R2dbcBatchJournalBatchingSpec.writeMessages

  override def typedSystem: ActorSystem[?] = system

  private lazy val journal = persistenceExt.journalFor("pekko.persistence.r2dbc.batched-journal")
  private val journalConnectionFactory =
    ConnectionFactoryProvider(system).connectionFactoryFor(
      "pekko.persistence.r2dbc.queue-limit-test-connection-factory")

  "R2dbcBatchJournal queue limit" should {

    "reject a write when the queue is full" in {
      val entityType = nextEntityType()
      val pids = (1 to 6).map(_ => nextPid(entityType))
      val probes = pids.map(_ => createTestProbe[Any]())

      // hold the only pooled connection so the flush of the first write stays in progress
      val blockingConnection = journalConnectionFactory.create().asFuture().futureValue

      // the first write starts a flush that waits for the held connection, so at most one
      // write request is removed from the queue before it is released. Writes 1 - 3 are
      // therefore always accepted, and write 6 is always rejected by the full queue
      pids.zip(probes).foreach {
        case (pid, probe) =>
          journal ! writeMessages(pid, 1L, s"e-$pid", probe.ref)
      }

      // release the connection so the buffered writes can complete and the write
      // results are delivered
      blockingConnection.close().asFuture().futureValue

      val failed = probes(5).expectMessageType[WriteMessagesFailed](10.seconds)
      failed.cause.getMessage shouldBe "Unable to accept the request, max-queue-size [3] reached"
      val failure = probes(5).expectMessageType[WriteMessageFailure](10.seconds)
      failure.message.persistenceId shouldBe pids(5)

      // the accepted writes complete after the connection is released
      pids.take(3).zip(probes.take(3)).foreach {
        case (pid, probe) =>
          probe.expectMessage(10.seconds, WriteMessagesSuccessful)
          probe.expectMessageType[WriteMessageSuccess](10.seconds).persistent.persistenceId shouldBe pid
      }
    }

  }

}
