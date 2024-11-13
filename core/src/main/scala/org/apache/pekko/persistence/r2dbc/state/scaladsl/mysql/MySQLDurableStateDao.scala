/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.state.scaladsl.mysql

import java.time.Instant

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import io.r2dbc.spi.ConnectionFactory
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.R2dbcSettings
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.journal.mysql.MySQLJournalDao
import pekko.persistence.r2dbc.state.scaladsl.DurableStateDao

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc]class MySQLDurableStateDao(
    settings: R2dbcSettings,
    connectionFactory: ConnectionFactory
)(implicit ec: ExecutionContext, system: ActorSystem[_]) extends DurableStateDao(settings, connectionFactory) {
  MySQLJournalDao.settingRequirements(settings)

  override lazy val transactionTimestampSql: String = "NOW(6)"

  override def selectBucketsSql(minSlice: Int, maxSlice: Int): String = {
    sql"""
     SELECT CAST(UNIX_TIMESTAMP(db_timestamp) AS SIGNED) / 10 AS bucket, count(*) AS count
     FROM $stateTable
     WHERE entity_type = ?
     AND slice BETWEEN $minSlice AND $maxSlice
     AND db_timestamp >= ? AND db_timestamp <= ?
     GROUP BY bucket ORDER BY bucket LIMIT ?
     """
  }

  override def stateBySlicesRangeSql(
      maxDbTimestampParam: Boolean,
      behindCurrentTime: FiniteDuration,
      backtracking: Boolean,
      minSlice: Int,
      maxSlice: Int): String = {

    def maxDbTimestampParamCondition =
      if (maxDbTimestampParam) s"AND db_timestamp < ?" else ""

    def behindCurrentTimeIntervalCondition =
      if (behindCurrentTime > Duration.Zero)
        s"AND db_timestamp < DATE_SUB(NOW(6), INTERVAL '${behindCurrentTime.toMicros}' MICROSECOND)"
      else ""

    val selectColumns =
      if (backtracking)
        "SELECT persistence_id, revision, db_timestamp, NOW(6) AS read_db_timestamp "
      else
        "SELECT persistence_id, revision, db_timestamp, NOW(6) AS read_db_timestamp, state_ser_id, state_ser_manifest, state_payload "

    sql"""
      $selectColumns
      FROM $stateTable
      WHERE entity_type = ?
      AND slice BETWEEN $minSlice AND $maxSlice
      AND db_timestamp >= ? $maxDbTimestampParamCondition $behindCurrentTimeIntervalCondition
      ORDER BY db_timestamp, revision
      LIMIT ?"""
  }

  override def currentDbTimestamp(): Future[Instant] = Future.successful(Instant.now())
}
