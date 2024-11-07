/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.mysql.query

import java.time.Instant

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import io.r2dbc.spi.ConnectionFactory
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.R2dbcSettings
import pekko.persistence.r2dbc.internal.Sql
import pekko.persistence.r2dbc.internal.Sql.ConfigurableInterpolation
import pekko.persistence.r2dbc.query.scaladsl.QueryDao

class MySQLQueryDao(
    journalSettings: R2dbcSettings,
    connectionFactory: ConnectionFactory
)(implicit ec: ExecutionContext, system: ActorSystem[_]) extends QueryDao(journalSettings, connectionFactory) {

  override implicit lazy val sqlReplacements: Sql.Replacements = Sql.Replacements.None
  override lazy val statementTimestampSql: String = "NOW(6)"

  override def eventsBySlicesRangeSql(
      toDbTimestampParam: Boolean,
      behindCurrentTime: FiniteDuration,
      backtracking: Boolean,
      minSlice: Int,
      maxSlice: Int): String = {

    def toDbTimestampParamCondition =
      if (toDbTimestampParam) "AND db_timestamp <= ?" else ""

    def behindCurrentTimeIntervalCondition =
      if (behindCurrentTime > Duration.Zero)
        s"AND db_timestamp < DATE_SUB($statementTimestampSql, INTERVAL '${behindCurrentTime.toMicros}' MICROSECOND)"
      else ""

    val selectColumns = {
      if (backtracking)
        s"SELECT slice, persistence_id, seq_nr, db_timestamp, $statementTimestampSql AS read_db_timestamp "
      else
        s"SELECT slice, persistence_id, seq_nr, db_timestamp, $statementTimestampSql AS read_db_timestamp, event_ser_id, event_ser_manifest, event_payload, meta_ser_id, meta_ser_manifest, meta_payload "
    }

    sql"""
      $selectColumns
      FROM $journalTable
      WHERE entity_type = ?
      AND slice BETWEEN $minSlice AND $maxSlice
      AND db_timestamp >= ? $toDbTimestampParamCondition $behindCurrentTimeIntervalCondition
      AND deleted = false
      ORDER BY db_timestamp, seq_nr
      LIMIT ?"""
  }

  override def selectBucketsSql(minSlice: Int, maxSlice: Int): String = {
    sql"""
      SELECT CAST(UNIX_TIMESTAMP(db_timestamp) AS SIGNED) / 10 AS bucket, count(*) AS count
      FROM $journalTable
      WHERE entity_type = ?
      AND slice BETWEEN $minSlice AND $maxSlice
      AND db_timestamp >= ? AND db_timestamp <= ?
      AND deleted = false
      GROUP BY bucket ORDER BY bucket LIMIT ?
      """
  }

  override def currentDbTimestamp(): Future[Instant] = Future.successful(Instant.now())
}
