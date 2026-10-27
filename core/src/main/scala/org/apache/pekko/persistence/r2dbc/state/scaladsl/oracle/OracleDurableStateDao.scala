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

package org.apache.pekko.persistence.r2dbc.state.scaladsl.oracle

import java.time.Instant

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.StateSettings
import pekko.persistence.r2dbc.internal.InstantFactory
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.internal.oracle.OracleUtils
import pekko.persistence.r2dbc.journal.mysql.MySQLJournalDao
import pekko.persistence.r2dbc.state.scaladsl.DurableStateDao
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Statement

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class OracleDurableStateDao(
    settings: StateSettings,
    connectionFactory: ConnectionFactory,
    closeCallsExceeding: Option[FiniteDuration] = None
)(implicit ec: ExecutionContext, system: ActorSystem[?])
    extends DurableStateDao(settings, connectionFactory, closeCallsExceeding) {
  OracleUtils.settingRequirements(settings)

  override protected def transactionTimestampSql: String = OracleUtils.currentTimestampSql

  // tags are stored as a JSON array, like for MySQL
  override protected def bindTagsForWrite(stmt: Statement, tags: Set[String], index: Int): Statement =
    if (tags.isEmpty) stmt.bindNull(index, classOf[String])
    else stmt.bind(index, MySQLJournalDao.tagsToJson(tags))

  override def selectBucketsSql(minSlice: Int, maxSlice: Int): String =
    sql"""
     SELECT ${OracleUtils.bucketSql} AS bucket, count(*) AS count
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

    val selectColumns =
      if (backtracking)
        s"SELECT persistence_id, revision, db_timestamp, ${OracleUtils.currentTimestampSql} AS read_db_timestamp, state_ser_id "
      else
        s"SELECT persistence_id, revision, db_timestamp, ${OracleUtils.currentTimestampSql} AS read_db_timestamp, state_ser_id, state_ser_manifest, state_payload "

    sql"""
      $selectColumns
      FROM $stateTable
      WHERE entity_type = ?
      AND slice BETWEEN $minSlice AND $maxSlice
      AND db_timestamp >= ? $maxDbTimestampParamCondition ${OracleUtils.behindCurrentTimeIntervalCondition(
        behindCurrentTime)}
      ORDER BY db_timestamp, revision
      LIMIT ?"""
  }

  override def currentDbTimestamp(): Future[Instant] = Future.successful(InstantFactory.now())
}
