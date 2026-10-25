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

package org.apache.pekko.persistence.r2dbc.snapshot.h2

import java.time.Instant

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Row
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.SnapshotSettings
import pekko.persistence.r2dbc.internal.InstantFactory
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.internal.h2.H2Utils
import pekko.persistence.r2dbc.snapshot.SnapshotDao

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class H2SnapshotDao(
    settings: SnapshotSettings, connectionFactory: ConnectionFactory,
    closeCallsExceeding: Option[FiniteDuration] = None
)(implicit ec: ExecutionContext, system: ActorSystem[?])
    extends SnapshotDao(settings, connectionFactory, closeCallsExceeding) {

  override protected def createUpsertSql: String =
    if (settings.startFromSnapshotEnabled)
      sql"""
        MERGE INTO $snapshotTable
        (slice, entity_type, persistence_id, seq_nr, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest, db_timestamp, tags)
        KEY (persistence_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
    else
      sql"""
        MERGE INTO $snapshotTable
        (slice, entity_type, persistence_id, seq_nr, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest)
        KEY (persistence_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""

  override protected def tagsFromRow(row: Row): Set[String] =
    H2Utils.tagsFromDb(row, "tags")

  override protected def selectBucketsSql(minSlice: Int, maxSlice: Int): String =
    sql"""
      SELECT CAST(EXTRACT(EPOCH FROM db_timestamp) AS BIGINT) / 10 AS bucket, count(*) AS count
      FROM $snapshotTable
      WHERE entity_type = ?
      AND ${sliceCondition(minSlice, maxSlice)}
      AND db_timestamp >= ? AND db_timestamp <= ?
      GROUP BY bucket ORDER BY bucket LIMIT ?
      """

  // the database runs in the same process, app timestamp is db timestamp, and later than the timestamps of
  // all completed writes
  override def currentDbTimestamp(): Future[Instant] = Future.successful(InstantFactory.nowStrictlyIncreasing())
}
