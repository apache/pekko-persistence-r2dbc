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

package org.apache.pekko.persistence.r2dbc.journal.mysql

import scala.concurrent.ExecutionContext
import io.r2dbc.spi.ConnectionFactory
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.R2dbcSettings
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.journal.JournalDao

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] object MySQLJournalDao {
  def settingRequirements(journalSettings: R2dbcSettings): Unit = {
    // Application timestamps are used because MySQL does not have transaction_timestamp like Postgres. In future releases
    // they could be tried to be emulated, but the benefits are questionable - no matter where the timestamps are generated,
    // risk of clock skews remains.
    require(journalSettings.useAppTimestamp,
      "use-app-timestamp config must be on for MySQL support")
    // Supporting the non-monotonic increasing timestamps by incrementing the timestamp within the insert queries based on
    // latest row in the database seems to cause deadlocks when running tests like PersistTimestampSpec. Possibly this could
    // be fixed.
    require(journalSettings.dbTimestampMonotonicIncreasing,
      "db-timestamp-monotonic-increasing config must be on for MySQL support")
    // Also, missing RETURNING implementation makes grabbing the timestamp generated by the database less efficient - this
    // applies for both of the requirements above.
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class MySQLJournalDao(
    journalSettings: R2dbcSettings,
    connectionFactory: ConnectionFactory)(
    implicit ec: ExecutionContext, system: ActorSystem[_]
) extends JournalDao(journalSettings, connectionFactory) {
  MySQLJournalDao.settingRequirements(journalSettings)

  override lazy val timestampSql: String = "NOW(6)"

  override val insertEventWithParameterTimestampSql: String =
    sql"INSERT INTO $journalTable " +
    "(slice, entity_type, persistence_id, seq_nr, writer, adapter_manifest, event_ser_id, event_ser_manifest, " +
    "event_payload, tags, meta_ser_id, meta_ser_manifest, meta_payload, db_timestamp) " +
    s"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
}
