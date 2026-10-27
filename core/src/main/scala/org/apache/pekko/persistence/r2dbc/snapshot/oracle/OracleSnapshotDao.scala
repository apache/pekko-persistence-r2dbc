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

package org.apache.pekko.persistence.r2dbc.snapshot.oracle

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.SnapshotSettings
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.snapshot.SnapshotDao
import io.r2dbc.spi.ConnectionFactory

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class OracleSnapshotDao(
    settings: SnapshotSettings, connectionFactory: ConnectionFactory,
    closeCallsExceeding: Option[FiniteDuration] = None
)(implicit ec: ExecutionContext, system: ActorSystem[?])
    extends SnapshotDao(settings, connectionFactory, closeCallsExceeding) {

  override val upsertSql = sql"""
    MERGE INTO $snapshotTable t
    USING (SELECT ? AS slice, ? AS entity_type, ? AS persistence_id, ? AS seq_nr, ? AS write_timestamp, ? AS snapshot,
      ? AS ser_id, ? AS ser_manifest, ? AS meta_payload, ? AS meta_ser_id, ? AS meta_ser_manifest FROM dual) excluded
    ON (t.persistence_id = excluded.persistence_id)
    WHEN MATCHED THEN UPDATE SET
      t.seq_nr = excluded.seq_nr,
      t.write_timestamp = excluded.write_timestamp,
      t.snapshot = excluded.snapshot,
      t.ser_id = excluded.ser_id,
      t.ser_manifest = excluded.ser_manifest,
      t.meta_payload = excluded.meta_payload,
      t.meta_ser_id = excluded.meta_ser_id,
      t.meta_ser_manifest = excluded.meta_ser_manifest
    WHEN NOT MATCHED THEN INSERT
      (slice, entity_type, persistence_id, seq_nr, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest)
      VALUES (excluded.slice, excluded.entity_type, excluded.persistence_id, excluded.seq_nr, excluded.write_timestamp,
      excluded.snapshot, excluded.ser_id, excluded.ser_manifest, excluded.meta_payload, excluded.meta_ser_id,
      excluded.meta_ser_manifest)"""
}
