/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.snapshot.mysql

import scala.concurrent.ExecutionContext
import io.r2dbc.spi.ConnectionFactory
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.R2dbcSettings
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.snapshot.SnapshotDao

class MySQLSnapshotDao(
    settings: R2dbcSettings, connectionFactory: ConnectionFactory
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
