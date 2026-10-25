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

package org.apache.pekko.persistence.r2dbc.query.scaladsl.h2

import java.time.Instant

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.QuerySettings
import pekko.persistence.r2dbc.internal.InstantFactory
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.internal.h2.H2Utils
import pekko.persistence.r2dbc.query.scaladsl.QueryDao
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Row

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class H2QueryDao(
    querySettings: QuerySettings,
    connectionFactory: ConnectionFactory,
    closeCallsExceeding: Option[FiniteDuration] = None
)(implicit ec: ExecutionContext, system: ActorSystem[?])
    extends QueryDao(querySettings, connectionFactory, closeCallsExceeding) {

  override lazy val statementTimestampSql: String = "CURRENT_TIMESTAMP"

  override protected def tagsFromRow(row: Row): Set[String] =
    H2Utils.tagsFromDb(row, "tags")

  override protected def selectColumns(backtracking: Boolean): String =
    if (backtracking)
      s"SELECT slice, persistence_id, seq_nr, db_timestamp, $statementTimestampSql AS read_db_timestamp, tags, event_ser_id "
    else
      s"SELECT slice, persistence_id, seq_nr, db_timestamp, $statementTimestampSql AS read_db_timestamp, tags, event_ser_id, event_ser_manifest, event_payload, meta_ser_id, meta_ser_manifest, meta_payload "

  override protected def behindCurrentTimeIntervalCondition(behindCurrentTime: FiniteDuration): String =
    if (behindCurrentTime > Duration.Zero)
      s"AND db_timestamp < CURRENT_TIMESTAMP - interval '${behindCurrentTime.toMillis.toDouble / 1000}' second"
    else ""

  override def eventsBySlicesRangeSql(
      toDbTimestampParam: Boolean,
      behindCurrentTime: FiniteDuration,
      backtracking: Boolean,
      minSlice: Int,
      maxSlice: Int): String =
    sql"""
      ${selectColumns(backtracking)}
      FROM $journalTable
      WHERE entity_type = ?
      AND slice BETWEEN $minSlice AND $maxSlice
      AND db_timestamp >= ? ${toDbTimestampParamCondition(toDbTimestampParam)} ${behindCurrentTimeIntervalCondition(
        behindCurrentTime)}
      AND deleted = false
      ORDER BY db_timestamp, seq_nr
      LIMIT ?"""

  override def selectBucketsSql(minSlice: Int, maxSlice: Int): String =
    sql"""
      SELECT CAST(EXTRACT(EPOCH FROM db_timestamp) AS BIGINT) / 10 AS bucket, count(*) AS count
      FROM $journalTable
      WHERE entity_type = ?
      AND slice BETWEEN $minSlice AND $maxSlice
      AND db_timestamp >= ? AND db_timestamp <= ?
      AND deleted = false
      GROUP BY bucket ORDER BY bucket LIMIT ?
      """

  // the database runs in the same process, app timestamp is db timestamp, and later than the timestamps of
  // all completed writes
  override def currentDbTimestamp(): Future[Instant] = Future.successful(InstantFactory.nowStrictlyIncreasing())
}
