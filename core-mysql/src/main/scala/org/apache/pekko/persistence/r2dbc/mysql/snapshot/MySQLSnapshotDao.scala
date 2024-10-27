/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.mysql.snapshot

import io.r2dbc.spi.ConnectionFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.dispatch.ExecutionContexts
import org.apache.pekko.persistence.Persistence
import org.apache.pekko.persistence.SnapshotSelectionCriteria
import org.apache.pekko.persistence.r2dbc.R2dbcSettings
import org.apache.pekko.persistence.r2dbc.internal.R2dbcExecutor
import org.apache.pekko.persistence.r2dbc.mysql.journal.MySQLJournalDao.log
import org.apache.pekko.persistence.r2dbc.snapshot.SnapshotDao
import org.apache.pekko.persistence.r2dbc.snapshot.SnapshotDao.SerializedSnapshotMetadata
import org.apache.pekko.persistence.r2dbc.snapshot.SnapshotDao.collectSerializedSnapshot
import org.apache.pekko.persistence.typed.PersistenceId

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class MySQLSnapshotDao(
    settings: R2dbcSettings, connectionFactory: ConnectionFactory
)(implicit ec: ExecutionContext, system: ActorSystem[_]) extends SnapshotDao(settings, connectionFactory) {

  private val persistenceExt = Persistence(system)

  private val r2dbcExecutor = new R2dbcExecutor(connectionFactory, log, settings.logDbCallsExceeding)

  private val snapshotTable = settings.snapshotsTableWithSchema

  private val upsertSql = s"""
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

  private def selectSql(criteria: SnapshotSelectionCriteria): String = {
    val maxSeqNrCondition =
      if (criteria.maxSequenceNr != Long.MaxValue) " AND seq_nr <= ?"
      else ""

    val minSeqNrCondition =
      if (criteria.minSequenceNr > 0L) " AND seq_nr >= ?"
      else ""

    val maxTimestampCondition =
      if (criteria.maxTimestamp != Long.MaxValue) " AND write_timestamp <= ?"
      else ""

    val minTimestampCondition =
      if (criteria.minTimestamp != 0L) " AND write_timestamp >= ?"
      else ""

    s"""
      SELECT persistence_id, seq_nr, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest
      FROM $snapshotTable
      WHERE persistence_id = ?
      $maxSeqNrCondition $minSeqNrCondition $maxTimestampCondition $minTimestampCondition
      LIMIT 1"""
  }

  private def deleteSql(criteria: SnapshotSelectionCriteria): String = {
    val maxSeqNrCondition =
      if (criteria.maxSequenceNr != Long.MaxValue) " AND seq_nr <= ?"
      else ""

    val minSeqNrCondition =
      if (criteria.minSequenceNr > 0L) " AND seq_nr >= ?"
      else ""

    val maxTimestampCondition =
      if (criteria.maxTimestamp != Long.MaxValue) " AND write_timestamp <= ?"
      else ""

    val minTimestampCondition =
      if (criteria.minTimestamp != 0L) " AND write_timestamp >= ?"
      else ""

    s"""
      DELETE FROM $snapshotTable
      WHERE persistence_id = ?
      $maxSeqNrCondition $minSeqNrCondition $maxTimestampCondition $minTimestampCondition"""
  }

  override def load(
      persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SnapshotDao.SerializedSnapshotRow]] = {
    r2dbcExecutor
      .select(s"select snapshot [$persistenceId], criteria: [$criteria]")(
        { connection =>
          val statement = connection
            .createStatement(selectSql(criteria))
            .bind(0, persistenceId)

          var bindIdx = 0
          if (criteria.maxSequenceNr != Long.MaxValue) {
            bindIdx += 1
            statement.bind(bindIdx, criteria.maxSequenceNr)
          }
          if (criteria.minSequenceNr > 0L) {
            bindIdx += 1
            statement.bind(bindIdx, criteria.minSequenceNr)
          }
          if (criteria.maxTimestamp != Long.MaxValue) {
            bindIdx += 1
            statement.bind(bindIdx, criteria.maxTimestamp)
          }
          if (criteria.minTimestamp > 0L) {
            bindIdx += 1
            statement.bind(bindIdx, criteria.minTimestamp)
          }
          statement
        },
        collectSerializedSnapshot)
      .map(_.headOption)(ExecutionContexts.parasitic)
  }

  override def store(serializedRow: SnapshotDao.SerializedSnapshotRow): Future[Unit] = {
    val entityType = PersistenceId.extractEntityType(serializedRow.persistenceId)
    val slice = persistenceExt.sliceForPersistenceId(serializedRow.persistenceId)

    r2dbcExecutor
      .updateOne(s"upsert snapshot [${serializedRow.persistenceId}], sequence number [${serializedRow.seqNr}]") {
        connection =>
          val statement =
            connection
              .createStatement(upsertSql)
              .bind(0, slice)
              .bind(1, entityType)
              .bind(2, serializedRow.persistenceId)
              .bind(3, serializedRow.seqNr)
              .bind(4, serializedRow.writeTimestamp)
              .bind(5, serializedRow.snapshot)
              .bind(6, serializedRow.serializerId)
              .bind(7, serializedRow.serializerManifest)

          serializedRow.metadata match {
            case Some(SerializedSnapshotMetadata(serializedMeta, serializerId, serializerManifest)) =>
              statement
                .bind(8, serializedMeta)
                .bind(9, serializerId)
                .bind(10, serializerManifest)
            case None =>
              statement
                .bindNull(8, classOf[Array[Byte]])
                .bindNull(9, classOf[Integer])
                .bindNull(10, classOf[String])
          }

          statement
      }
      .map(_ => ())(ExecutionContexts.parasitic)
  }

  override def delete(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
    r2dbcExecutor
      .updateOne(s"delete snapshot [$persistenceId], criteria [$criteria]") { connection =>
        val statement = connection
          .createStatement(deleteSql(criteria))
          .bind(0, persistenceId)

        var bindIdx = 0
        if (criteria.maxSequenceNr != Long.MaxValue) {
          bindIdx += 1
          statement.bind(bindIdx, criteria.maxSequenceNr)
        }
        if (criteria.minSequenceNr > 0L) {
          bindIdx += 1
          statement.bind(bindIdx, criteria.minSequenceNr)
        }
        if (criteria.maxTimestamp != Long.MaxValue) {
          bindIdx += 1
          statement.bind(bindIdx, criteria.maxTimestamp)
        }
        if (criteria.minTimestamp > 0L) {
          bindIdx += 1
          statement.bind(bindIdx, criteria.minTimestamp)
        }
        statement
      }.map(_ => ())(ExecutionContexts.parasitic)
  }
}
