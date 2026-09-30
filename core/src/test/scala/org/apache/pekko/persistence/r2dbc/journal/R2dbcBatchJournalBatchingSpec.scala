/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.persistence.r2dbc.journal

import scala.concurrent.duration._
import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.LoggingTestKit
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorRef
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.persistence.AtomicWrite
import pekko.persistence.JournalProtocol.WriteMessageRejected
import pekko.persistence.JournalProtocol.WriteMessageSuccess
import pekko.persistence.JournalProtocol.WriteMessages
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
      pekko.loglevel = DEBUG
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

  // A long batch window so only the size trigger can flush, used to assert that a stale FlushDone
  // does not start a second flush while the first one is still in progress
  val staleFlushDoneConfig: Config = ConfigFactory
    .parseString("""
      pekko.loglevel = DEBUG
      pekko.persistence.r2dbc {
        batched-journal {
          max-queue-size = 3
          max-batch-size = 1
          max-batch-time = 10s
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
      Seq(AtomicWrite(PersistentRepr(event, seqNr, pid))),
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

class R2dbcBatchJournalStaleFlushDoneSpec
    extends ScalaTestWithActorTestKit(R2dbcBatchJournalBatchingSpec.staleFlushDoneConfig)
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

  "R2dbcBatchJournal stale FlushDone" should {

    "ignore a FlushDone from a previous incarnation" in {
      val entityType = nextEntityType()
      val pids = (1 to 2).map(_ => nextPid(entityType))
      val probes = pids.map(_ => createTestProbe[Any]())

      // hold the only pooled connection so the flush of the first write stays in progress
      val blockingConnection = journalConnectionFactory.create().asFuture().futureValue

      // the first write starts a flush that is blocked waiting for the held connection
      LoggingTestKit.debug("flushing [1] write requests").expect {
        journal ! writeMessages(pids(0), 1L, s"e-${pids(0)}", probes(0).ref)
      }

      // a FlushDone carrying a generation from a previous incarnation must be ignored:
      // no second flush may start while the first one is still in progress
      journal ! R2dbcBatchJournal.FlushDone(-1L)
      LoggingTestKit.debug("flushing").withOccurrences(0).expect {
        journal ! writeMessages(pids(1), 1L, s"e-${pids(1)}", probes(1).ref)
      }

      // release the connection so the blocked flush and then the second write can complete
      blockingConnection.close().asFuture().futureValue

      pids.zip(probes).foreach {
        case (pid, probe) =>
          probe.expectMessage(10.seconds, WriteMessagesSuccessful)
          probe.expectMessageType[WriteMessageSuccess](10.seconds).persistent.persistenceId shouldBe pid
      }
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

    "reject writes beyond the queue limit" in {
      val entityType = nextEntityType()
      val pids = (1 to 6).map(_ => nextPid(entityType))
      val probes = pids.map(_ => createTestProbe[Any]())

      // hold the only pooled connection so the flush of the first write stays in progress
      val blockingConnection = journalConnectionFactory.create().asFuture().futureValue

      // wait until the flush has dequeued the first write. The queue then has room for
      // exactly max-queue-size (3) more writes, so writes 2 - 4 are accepted and
      // writes 5 and 6 are rejected
      LoggingTestKit.debug("flushing [1] write requests").expect {
        journal ! writeMessages(pids(0), 1L, s"e-${pids(0)}", probes(0).ref)
      }

      pids.drop(1).zip(probes.drop(1)).foreach {
        case (pid, probe) =>
          journal ! writeMessages(pid, 1L, s"e-$pid", probe.ref)
      }

      // release the connection so the accepted writes can complete
      blockingConnection.close().asFuture().futureValue

      pids.take(4).zip(probes.take(4)).foreach {
        case (pid, probe) =>
          probe.expectMessage(10.seconds, WriteMessagesSuccessful)
          probe.expectMessageType[WriteMessageSuccess](10.seconds).persistent.persistenceId shouldBe pid
      }

      pids.takeRight(2).zip(probes.takeRight(2)).foreach {
        case (pid, probe) =>
          probe.expectMessage(10.seconds, WriteMessagesSuccessful)
          val rejected = probe.expectMessageType[WriteMessageRejected](10.seconds)
          rejected.message.persistenceId shouldBe pid
          rejected.cause.getMessage shouldBe "Unable to accept the request, max-queue-size [3] reached"
      }
    }

    "not trip the journal circuit breaker when the queue is full" in {
      val entityType = nextEntityType()
      val pids = (1 to 17).map(_ => nextPid(entityType))
      val probes = pids.map(_ => createTestProbe[Any]())

      // hold the only pooled connection so the flush of the first write stays in progress
      val blockingConnection = journalConnectionFactory.create().asFuture().futureValue

      // wait until the flush has dequeued the first write
      LoggingTestKit.debug("flushing [1] write requests").expect {
        journal ! writeMessages(pids(0), 1L, s"e-${pids(0)}", probes(0).ref)
      }

      // writes 2 - 4 fill the queue; writes 5 - 16 are rejected, which is more than the
      // default circuit-breaker max-failures (10). The rejections are returned as
      // per-message rejections in a successful Future, so they must not count as
      // circuit-breaker failures. The rejections are delivered immediately, but with
      // write-response-global-order = on (the default) the AsyncWriteJournal resequencer
      // orders responses by request arrival, so they only reach the probes after the
      // blocked write 1 has completed.
      pids.slice(1, 16).zip(probes.slice(1, 16)).foreach {
        case (pid, probe) =>
          journal ! writeMessages(pid, 1L, s"e-$pid", probe.ref)
      }

      // release the connection so the accepted writes complete
      blockingConnection.close().asFuture().futureValue

      pids.take(4).zip(probes.take(4)).foreach {
        case (pid, probe) =>
          probe.expectMessage(10.seconds, WriteMessagesSuccessful)
          probe.expectMessageType[WriteMessageSuccess](10.seconds).persistent.persistenceId shouldBe pid
      }

      pids.slice(4, 16).zip(probes.slice(4, 16)).foreach {
        case (pid, probe) =>
          probe.expectMessage(10.seconds, WriteMessagesSuccessful)
          val rejected = probe.expectMessageType[WriteMessageRejected](10.seconds)
          rejected.message.persistenceId shouldBe pid
          rejected.cause.getMessage shouldBe "Unable to accept the request, max-queue-size [3] reached"
      }

      // if the rejections had tripped the circuit breaker, this write would fail
      journal ! writeMessages(pids(16), 1L, s"e-${pids(16)}", probes(16).ref)
      probes(16).expectMessage(10.seconds, WriteMessagesSuccessful)
      probes(16).expectMessageType[WriteMessageSuccess](10.seconds).persistent.persistenceId shouldBe pids(16)
    }

  }

}
