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

package org.apache.pekko.persistence.r2dbc.snapshot.mysql

import scala.concurrent.ExecutionContext
import io.r2dbc.spi.ConnectionFactory
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.SnapshotSettings
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.snapshot.SnapshotDao

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class MySQLSnapshotDao(
    settings: SnapshotSettings, connectionFactory: ConnectionFactory
)(implicit ec: ExecutionContext, system: ActorSystem[_]) extends SnapshotDao(settings, connectionFactory) {

  override val upsertSql = sql"""
    INSERT INTO $snapshotTable
    (slice, entity_type, persistence_id, seq_nr, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS excluded
    ON DUPLICATE KEY UPDATE
      seq_nr = excluded.seq_nr,
      write_timestamp = excluded.write_timestamp,
      snapshot = excluded.snapshot,
      ser_id = excluded.ser_id,
      ser_manifest = excluded.ser_manifest,
      meta_payload = excluded.meta_payload,
      meta_ser_id = excluded.meta_ser_id,
      meta_ser_manifest = excluded.meta_ser_manifest"""
}
