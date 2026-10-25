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

package org.apache.pekko.persistence.r2dbc.state.scaladsl.h2

import java.time.Instant

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.StateSettings
import pekko.persistence.r2dbc.internal.InstantFactory
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.internal.h2.H2Utils
import pekko.persistence.r2dbc.state.scaladsl.DurableStateDao
import io.r2dbc.spi.ConnectionFactory

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class H2DurableStateDao(
    settings: StateSettings,
    connectionFactory: ConnectionFactory,
    closeCallsExceeding: Option[FiniteDuration] = None
)(implicit ec: ExecutionContext, system: ActorSystem[?])
    extends DurableStateDao(settings, connectionFactory, closeCallsExceeding) {
  // always app timestamp (db is same process) monotonic increasing
  H2Utils.settingRequirements(settings)

  // Not CURRENT_TIMESTAMP: H2 reads the JVM clock, which can return the same value for a while (about 0.5 ms on
  // Windows), and rounds it to microseconds. A write could then get a timestamp that isn't before a later
  // currentDbTimestamp(), and the by slice queries would miss it.
  override protected def transactionTimestampSql: String =
    H2Utils.timestampLiteral(InstantFactory.nowStrictlyIncreasing())

  override def selectBucketsSql(minSlice: Int, maxSlice: Int): String =
    sql"""
     SELECT CAST(EXTRACT(EPOCH FROM db_timestamp) AS BIGINT) / 10 AS bucket, count(*) AS count
     FROM $stateTable
     WHERE entity_type = ?
     AND slice BETWEEN $minSlice AND $maxSlice
     AND db_timestamp >= ? AND db_timestamp <= ?
     GROUP BY bucket ORDER BY bucket LIMIT ?
     """

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
        s"AND db_timestamp < CURRENT_TIMESTAMP - interval '${behindCurrentTime.toMillis.toDouble / 1000}' second"
      else ""

    val selectColumns =
      if (backtracking)
        "SELECT persistence_id, revision, db_timestamp, CURRENT_TIMESTAMP AS read_db_timestamp, state_ser_id "
      else
        "SELECT persistence_id, revision, db_timestamp, CURRENT_TIMESTAMP AS read_db_timestamp, state_ser_id, state_ser_manifest, state_payload "

    sql"""
      $selectColumns
      FROM $stateTable
      WHERE entity_type = ?
      AND slice BETWEEN $minSlice AND $maxSlice
      AND db_timestamp >= ? $maxDbTimestampParamCondition $behindCurrentTimeIntervalCondition
      ORDER BY db_timestamp, revision
      LIMIT ?"""
  }

  // the database runs in the same process, app timestamp is db timestamp
  override def currentDbTimestamp(): Future[Instant] = Future.successful(InstantFactory.nowStrictlyIncreasing())
}
