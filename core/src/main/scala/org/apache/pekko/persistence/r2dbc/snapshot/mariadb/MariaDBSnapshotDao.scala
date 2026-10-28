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

package org.apache.pekko.persistence.r2dbc.snapshot.mariadb

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import io.r2dbc.spi.ConnectionFactory
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.SnapshotSettings
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.snapshot.mysql.MySQLSnapshotDao

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class MariaDBSnapshotDao(
    settings: SnapshotSettings, connectionFactory: ConnectionFactory,
    closeCallsExceeding: Option[FiniteDuration] = None
)(implicit ec: ExecutionContext, system: ActorSystem[?])
    extends MySQLSnapshotDao(settings, connectionFactory, closeCallsExceeding) {

  // MariaDB doesn't support the `AS excluded` row alias of MySQL, the inserted values are referenced with VALUES()
  override val upsertSql = sql"""
    INSERT INTO $snapshotTable
    (slice, entity_type, persistence_id, seq_nr, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON DUPLICATE KEY UPDATE
      seq_nr = VALUES(seq_nr),
      write_timestamp = VALUES(write_timestamp),
      snapshot = VALUES(snapshot),
      ser_id = VALUES(ser_id),
      ser_manifest = VALUES(ser_manifest),
      meta_payload = VALUES(meta_payload),
      meta_ser_id = VALUES(meta_ser_id),
      meta_ser_manifest = VALUES(meta_ser_manifest)"""
}
