/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.internal

import org.apache.pekko
import pekko.NotUsed
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.R2dbcSettings
import pekko.persistence.r2dbc.journal.JournalDao.SerializedJournalRow
import pekko.persistence.r2dbc.query.scaladsl.QueryDao
import pekko.stream.scaladsl.Source
import org.slf4j.LoggerFactory

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] object EventsByPersistenceId {
  private final case class ByPersistenceIdState(queryCount: Int, rowCount: Int, latestSeqNr: Long)
}

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class EventsByPersistenceId(
    settings: R2dbcSettings,
    queryDao: QueryDao
) {
  import EventsByPersistenceId._

  private val log = LoggerFactory.getLogger(getClass)

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
      if (state.queryCount == 0L || state.rowCount >= settings.querySettings.bufferSize) {
        val newState = state.copy(rowCount = 0, queryCount = state.queryCount + 1)

        if (state.queryCount != 0 && log.isDebugEnabled())
          log.debug(
            "currentEventsByPersistenceId query [{}] for persistenceId [{}], from [{}] to [{}]. Found [{}] rows in previous query.",
            state.queryCount: java.lang.Integer,
            persistenceId,
            state.latestSeqNr + 1: java.lang.Long,
            highestSeqNr: java.lang.Long,
            state.rowCount: java.lang.Integer)

        newState -> Some(
          queryDao
            .eventsByPersistenceId(persistenceId, state.latestSeqNr + 1, highestSeqNr))
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
}
