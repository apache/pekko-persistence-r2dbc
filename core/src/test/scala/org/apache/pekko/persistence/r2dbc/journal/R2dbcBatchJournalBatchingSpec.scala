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
import pekko.persistence.JournalProtocol.WriteMessageSuccess
import pekko.persistence.JournalProtocol.WriteMessages
import pekko.persistence.JournalProtocol.WriteMessagesSuccessful
import pekko.persistence.PersistentRepr
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object R2dbcBatchJournalBatchingSpec {

  // writes are held until max-batch-size is reached, the long batch window
  // guarantees that only the size trigger can complete the writes in the test
  val sizeFlushConfig: Config = ConfigFactory
    .parseString("""
      pekko.persistence.r2dbc.journal {
        max-batch-size = 3
        max-batch-time = 10s
      }""")
    .withFallback(R2dbcBatchJournalSpec.config)

  // a single write below max-batch-size is flushed when the batch window expires
  val timerFlushConfig: Config = ConfigFactory
    .parseString("pekko.persistence.r2dbc.journal.max-batch-time = 500ms")
    .withFallback(R2dbcBatchJournalSpec.config)

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
    with LogCapturing {
  import R2dbcBatchJournalBatchingSpec.writeMessages

  override def typedSystem: ActorSystem[?] = system

  private val journal = persistenceExt.journalFor("pekko.persistence.r2dbc.journal")

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
    with LogCapturing {
  import R2dbcBatchJournalBatchingSpec.writeMessages

  override def typedSystem: ActorSystem[?] = system

  private val journal = persistenceExt.journalFor("pekko.persistence.r2dbc.journal")

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
