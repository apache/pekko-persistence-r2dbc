/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pekko.persistence.r2dbc.query.scaladsl.mysql

import java.time.Instant

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import io.r2dbc.spi.ConnectionFactory
import org.apache.pekko
import org.apache.pekko.persistence.r2dbc.QuerySettings
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.query.scaladsl.QueryDao

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class MySQLQueryDao(
    querySettings: QuerySettings,
    connectionFactory: ConnectionFactory
)(implicit ec: ExecutionContext, system: ActorSystem[_]) extends QueryDao(querySettings, connectionFactory) {

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
