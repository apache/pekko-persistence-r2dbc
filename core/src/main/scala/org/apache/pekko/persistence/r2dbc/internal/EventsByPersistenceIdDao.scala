/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.internal

import java.time.Instant

import org.apache.pekko
import pekko.NotUsed
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.Dialect
import pekko.persistence.r2dbc.SharedSettings
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.journal.JournalDao.SerializedJournalRow
import pekko.persistence.r2dbc.journal.JournalDao.readMetadata
import pekko.stream.scaladsl.Source
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] object EventsByPersistenceIdDao {

  private val log = LoggerFactory.getLogger(classOf[EventsByPersistenceIdDao])

  private final case class ByPersistenceIdState(queryCount: Int, rowCount: Int, latestSeqNr: Long)
}

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] trait EventsByPersistenceIdDao {
  import EventsByPersistenceIdDao._

  implicit protected def ec: ExecutionContext

  protected def sharedSettings: SharedSettings

  implicit protected def dialect: Dialect
  protected def statementTimestampSql: String

  protected def journalTable: String

  protected def r2dbcExecutor: R2dbcExecutor

  // TODO try dropping lazy
  private lazy val selectEventsSql = sql"""
    SELECT slice, entity_type, persistence_id, seq_nr, db_timestamp, $statementTimestampSql AS read_db_timestamp, event_ser_id, event_ser_manifest, event_payload, writer, adapter_manifest, meta_ser_id, meta_ser_manifest, meta_payload
    from $journalTable
    WHERE persistence_id = ? AND seq_nr >= ? AND seq_nr <= ?
    AND deleted = false
    ORDER BY seq_nr
    LIMIT ?"""

  /**
   * INTERNAL API: Used by both journal replay and currentEventsByPersistenceId
   */
  @InternalApi private[r2dbc] def internalEventsByPersistenceId(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[SerializedJournalRow, NotUsed] = {
    def updateState(state: ByPersistenceIdState, row: SerializedJournalRow): ByPersistenceIdState =
      state.copy(rowCount = state.rowCount + 1, latestSeqNr = row.seqNr)

    def nextQuery(
        state: ByPersistenceIdState,
        highestSeqNr: Long): (ByPersistenceIdState, Option[Source[SerializedJournalRow, NotUsed]]) = {
      if (state.queryCount == 0L || state.rowCount >= sharedSettings.bufferSize) {
        val newState = state.copy(rowCount = 0, queryCount = state.queryCount + 1)

        if (state.queryCount != 0 && log.isDebugEnabled())
          log.debug(
            "currentEventsByPersistenceId query [{}] for persistenceId [{}], from [{}] to [{}]. Found [{}] rows in previous query.",
            state.queryCount: java.lang.Integer,
            persistenceId,
            state.latestSeqNr + 1: java.lang.Long,
            highestSeqNr: java.lang.Long,
            state.rowCount: java.lang.Integer)

        newState -> Some(eventsByPersistenceId(persistenceId, state.latestSeqNr + 1, highestSeqNr))
      } else {
        log.debug(
          "currentEventsByPersistenceId query [{}] for persistenceId [{}] completed. Found [{}] rows in previous query.",
          state.queryCount: java.lang.Integer,
          persistenceId,
          state.rowCount: java.lang.Integer)

        state -> None
      }
    }

    if (log.isDebugEnabled())
      log.debug(
        "currentEventsByPersistenceId query for persistenceId [{}], from [{}] to [{}].",
        persistenceId,
        fromSequenceNr: java.lang.Long,
        toSequenceNr: java.lang.Long)

    ContinuousQuery[ByPersistenceIdState, SerializedJournalRow](
      initialState = ByPersistenceIdState(0, 0, latestSeqNr = fromSequenceNr - 1),
      updateState = updateState,
      delayNextQuery = _ => None,
      nextQuery = state => nextQuery(state, toSequenceNr))
  }

  def eventsByPersistenceId(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[SerializedJournalRow, NotUsed] = {

    val result = r2dbcExecutor.select(s"select eventsByPersistenceId [$persistenceId]")(
      connection =>
        connection
          .createStatement(selectEventsSql)
          .bind(0, persistenceId)
          .bind(1, fromSequenceNr)
          .bind(2, toSequenceNr)
          .bind(3, sharedSettings.bufferSize),
      row =>
        SerializedJournalRow(
          slice = row.get[Integer]("slice", classOf[Integer]),
          entityType = row.get("entity_type", classOf[String]),
          persistenceId = row.get("persistence_id", classOf[String]),
          seqNr = row.get[java.lang.Long]("seq_nr", classOf[java.lang.Long]),
          dbTimestamp = row.get("db_timestamp", classOf[Instant]),
          readDbTimestamp = row.get("read_db_timestamp", classOf[Instant]),
          payload = Some(row.get("event_payload", classOf[Array[Byte]])),
          serId = row.get[Integer]("event_ser_id", classOf[Integer]),
          serManifest = row.get("event_ser_manifest", classOf[String]),
          writerUuid = row.get("writer", classOf[String]),
          tags = Set.empty, // tags not fetched in queries (yet)
          metadata = readMetadata(row)))

    if (log.isDebugEnabled)
      result.foreach(rows => log.debug("Read [{}] events for persistenceId [{}]", rows.size, persistenceId))

    Source.futureSource(result.map(Source(_))).mapMaterializedValue(_ => NotUsed)
  }
}
