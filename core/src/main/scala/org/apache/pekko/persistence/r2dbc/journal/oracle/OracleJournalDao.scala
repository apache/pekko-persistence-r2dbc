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

package org.apache.pekko.persistence.r2dbc.journal.oracle

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.JournalSettings
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.internal.oracle.OracleUtils
import pekko.persistence.r2dbc.journal.JournalDao
import pekko.persistence.r2dbc.journal.mysql.MySQLJournalDao
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Row
import io.r2dbc.spi.Statement

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class OracleJournalDao(
    journalSettings: JournalSettings,
    connectionFactory: ConnectionFactory,
    closeCallsExceeding: Option[FiniteDuration] = None)(
    implicit ec: ExecutionContext, system: ActorSystem[?]
) extends JournalDao(journalSettings, connectionFactory, closeCallsExceeding) {
  OracleUtils.settingRequirements(journalSettings)

  override lazy val timestampSql: String = OracleUtils.currentTimestampSql
  override lazy val statementTimestampSql: String = OracleUtils.currentTimestampSql

  // tags are stored as a JSON array, like for MySQL
  override protected def bindTagsForWrite(stmt: Statement, tags: Set[String], index: Int): Statement =
    if (tags.isEmpty) stmt.bindNull(index, classOf[String])
    else stmt.bind(index, MySQLJournalDao.tagsToJson(tags))

  override protected def tagsFromRow(row: Row): Set[String] =
    MySQLJournalDao.tagsFromJson(row.get("tags", classOf[String]))

  override val insertEventWithParameterTimestampSql: String =
    sql"INSERT INTO $journalTable " +
    "(slice, entity_type, persistence_id, seq_nr, writer, adapter_manifest, event_ser_id, event_ser_manifest, " +
    "event_payload, tags, meta_ser_id, meta_ser_manifest, meta_payload, db_timestamp) " +
    s"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
}
