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

package org.apache.pekko.persistence.r2dbc.query

import java.time.Instant

import scala.concurrent.duration._
import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.query.NoOffset
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.query.typed.EventEnvelope
import pekko.persistence.r2dbc.Dialect
import pekko.persistence.r2dbc.R2dbcSettings
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.persistence.typed.PersistenceId
import pekko.serialization.SerializationExtension
import pekko.stream.testkit.TestSubscriber
import pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory

class EventsBySliceBacktrackingSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[_] = system
  private val settings = new R2dbcSettings(system.settings.config.getConfig("pekko.persistence.r2dbc"))

  private val query = PersistenceQuery(testKit.system)
    .readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)
  private val stringSerializer = SerializationExtension(system).serializerFor(classOf[String])
  private val log = LoggerFactory.getLogger(getClass)

  // to be able to store events with specific timestamps
  private def writeEvent(slice: Int, persistenceId: String, seqNr: Long, timestamp: Instant, event: String): Unit = {
    log.debug("Write test event [{}] [{}] [{}] at time [{}]", persistenceId, seqNr: java.lang.Long, event, timestamp)
    implicit val dialect: Dialect = settings.dialect
    val insertEventSql = sql"""
      INSERT INTO ${settings.journalTableWithSchema}
      (slice, entity_type, persistence_id, seq_nr, db_timestamp, writer, adapter_manifest, event_ser_id, event_ser_manifest, event_payload)
      VALUES (?, ?, ?, ?, ?, '', '', ?, '', ?)"""
    val entityType = PersistenceId.extractEntityType(persistenceId)

    val result = r2dbcExecutor.updateOne("test writeEvent") { connection =>
      connection
        .createStatement(insertEventSql)
        .bind(0, slice)
        .bind(1, entityType)
        .bind(2, persistenceId)
        .bind(3, seqNr)
        .bind(4, timestamp)
        .bind(5, stringSerializer.identifier)
        .bind(6, stringSerializer.toBinary(event))
    }
    result.futureValue shouldBe 1
  }

  "eventsBySlices backtracking" should {

    "find old events with earlier timestamp" in {
      // this scenario is handled by the backtracking query
      val entityType = nextEntityType()
      val pid1 = nextPid(entityType)
      val pid2 = nextPid(entityType)
      val slice1 = query.sliceForPersistenceId(pid1)
      val slice2 = query.sliceForPersistenceId(pid2)
      val sinkProbe = TestSink.probe[EventEnvelope[String]](system.classicSystem)

      // don't let behind-current-time be a reason for not finding events
      val startTime = Instant.now().minusSeconds(10 * 60)

      writeEvent(slice1, pid1, 1L, startTime, "e1-1")
      writeEvent(slice1, pid1, 2L, startTime.plusMillis(1), "e1-2")

      val result: TestSubscriber.Probe[EventEnvelope[String]] =
        query
          .eventsBySlices[String](entityType, 0, persistenceExt.numberOfSlices - 1, NoOffset)
          .runWith(sinkProbe)
          .request(100)

      val env1 = result.expectNext()
      env1.persistenceId shouldBe pid1
      env1.sequenceNr shouldBe 1L
      env1.eventOption shouldBe Some("e1-1")

      val env2 = result.expectNext()
      env2.persistenceId shouldBe pid1
      env2.sequenceNr shouldBe 2L
      env2.eventOption shouldBe Some("e1-2")

      // first backtracking query kicks in immediately after the first normal query has finished
      // and it also emits duplicates (by design)
      val env3 = result.expectNext()
      env3.persistenceId shouldBe pid1
      env3.sequenceNr shouldBe 1L
      // event payload isn't included in backtracking results
      env3.eventOption shouldBe None
      // but it can be lazy loaded
      query.loadEnvelope[String](env3.persistenceId, env3.sequenceNr).futureValue.eventOption shouldBe Some("e1-1")
      // backtracking up to (and equal to) the same offset
      val env4 = result.expectNext()
      env4.persistenceId shouldBe pid1
      env4.sequenceNr shouldBe 2L
      env4.eventOption shouldBe None

      result.expectNoMessage(100.millis) // not e1-2

      writeEvent(slice1, pid1, 3L, startTime.plusMillis(3), "e1-3")
      val env5 = result.expectNext()
      env5.persistenceId shouldBe pid1
      env5.sequenceNr shouldBe 3L

      // before e1-3 so it will not be found by the normal query
      writeEvent(slice2, pid2, 1L, startTime.plusMillis(2), "e2-1")

      // no backtracking yet
      result.expectNoMessage(settings.querySettings.refreshInterval + 100.millis)

      // after 1/2 of the backtracking widow, to kick off a backtracking query
      writeEvent(
        slice1,
        pid1,
        4L,
        startTime.plusMillis(settings.querySettings.backtrackingWindow.toMillis / 2).plusMillis(4),
        "e1-4")
      val env6 = result.expectNext()
      env6.persistenceId shouldBe pid1
      env6.sequenceNr shouldBe 4L

      // backtracking finds it,  and it also emits duplicates (by design)
      // e1-1 and e1-2 were already handled by previous backtracking query
      val env7 = result.expectNext()
      env7.persistenceId shouldBe pid2
      env7.sequenceNr shouldBe 1L

      val env8 = result.expectNext()
      env8.persistenceId shouldBe pid1
      env8.sequenceNr shouldBe 3L

      val env9 = result.expectNext()
      env9.persistenceId shouldBe pid1
      env9.sequenceNr shouldBe 4L
    }
  }

}
