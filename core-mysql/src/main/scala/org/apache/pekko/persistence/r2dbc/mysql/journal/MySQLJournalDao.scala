/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.mysql.journal

import java.time.Instant

import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Statement
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.dispatch.ExecutionContexts
import org.apache.pekko.persistence.Persistence
import org.apache.pekko.persistence.r2dbc.R2dbcSettings
import org.apache.pekko.persistence.r2dbc.internal.R2dbcExecutor
import org.apache.pekko.persistence.r2dbc.internal.Sql.Interpolation
import org.apache.pekko.persistence.r2dbc.journal.JournalDao
import org.apache.pekko.persistence.r2dbc.journal.JournalDao.SerializedJournalRow
import org.apache.pekko.persistence.r2dbc.mysql.journal.MySQLJournalDao.log
import org.apache.pekko.persistence.typed.PersistenceId
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object MySQLJournalDao {
  val log: Logger = LoggerFactory.getLogger(classOf[MySQLJournalDao])
}

class MySQLJournalDao(
    journalSettings: R2dbcSettings,
    connectionFactory: ConnectionFactory
)(implicit ec: ExecutionContext, system: ActorSystem[_])
    extends JournalDao(journalSettings, connectionFactory) {
  // Application timestamps are used because MySQL does not have transaction_timestamp like Postgres. In future releases
  // they could be tried to be emulated, but the benefits are questionable - no matter where the timestamps are generated,
  // risk of clock skews remains.
  require(journalSettings.useAppTimestamp,
    "use-app-timestamp config must be on for MySQL support")
  // Because MySQL support is using application timestamps, it does make sense to not trust that timestamps will be
  // monotonic increasing in the database - let's say cluster gets restarted and the shard that hosted certain entity
  // moves to a different node and the clocks are skewed between nodes, journal has to make sure that the timestamps
  // within the persistence ID are monotonic increasing to reduce the risk of issues with persistence queries.
  require(!journalSettings.dbTimestampMonotonicIncreasing,
    "db-timestamp-monotonic-increasing config must be off for MySQL support")

  private val persistenceExt = Persistence(system)

  private val r2dbcExecutor = new R2dbcExecutor(connectionFactory, log, journalSettings.logDbCallsExceeding)(ec, system)

  private val journalTable = journalSettings.journalTableWithSchema

  private val insertSql =
    s"INSERT INTO $journalTable " +
    "(slice, entity_type, persistence_id, seq_nr, writer, adapter_manifest, event_ser_id, event_ser_manifest, " +
    "event_payload, tags, meta_ser_id, meta_ser_manifest, meta_payload, db_timestamp) " +
    s"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
    s"GREATEST(?, IFNULL((SELECT DATE_ADD(db_timestamp, INTERVAL '1' MICROSECOND) FROM $journalTable AS subquery WHERE persistence_id = ? AND seq_nr = ?), 0)))"

  private val insertedDbTimestampSql =
    s"SELECT db_timestamp FROM $journalTable WHERE persistence_id = ? AND seq_nr = ?"

  private val selectHighestSequenceNrSql =
    s"SELECT MAX(seq_nr) FROM $journalTable WHERE persistence_id = ? AND seq_nr >= ?"

  private val deleteEventsSql = s"""
    DELETE FROM $journalTable
    WHERE persistence_id = ? AND seq_nr <= ?"""
  private val insertDeleteMarkerSql = s"""
    INSERT INTO $journalTable
    (slice, entity_type, persistence_id, seq_nr, db_timestamp, writer, adapter_manifest, event_ser_id, event_ser_manifest, event_payload, deleted)
    VALUES (?, ?, ?, ?, NOW(), ?, ?, ?, ?, ?, ?)"""

  override def writeEvents(events: Seq[JournalDao.SerializedJournalRow]): Future[Instant] = {
    require(events.nonEmpty)

    // it's always the same persistenceId for all events
    val persistenceId = events.head.persistenceId
    val highestSeqNr = events.view.map(_.seqNr).max
    val previousSeqNr = events.head.seqNr - 1

    def bind(stmt: Statement, write: SerializedJournalRow): Statement = {
      stmt
        .bind(0, write.slice)
        .bind(1, write.entityType)
        .bind(2, write.persistenceId)
        .bind(3, write.seqNr)
        .bind(4, write.writerUuid)
        .bind(5, "") // FIXME event adapter
        .bind(6, write.serId)
        .bind(7, write.serManifest)
        .bind(8, write.payload.get)

      if (write.tags.isEmpty)
        stmt.bindNull(9, classOf[Array[String]])
      else
        stmt.bind(9, write.tags.toArray)

      // optional metadata
      write.metadata match {
        case Some(m) =>
          stmt
            .bind(10, m.serId)
            .bind(11, m.serManifest)
            .bind(12, m.payload)
        case None =>
          stmt
            .bindNull(10, classOf[Integer])
            .bindNull(11, classOf[String])
            .bindNull(12, classOf[Array[Byte]])
      }

      stmt
        .bind(13, write.dbTimestamp)
        .bind(14, write.persistenceId)
        .bind(15, previousSeqNr)

      stmt
    }

    val totalEvents = events.size
    if (totalEvents == 1) {
      val result = r2dbcExecutor.updateOneThenSelect(s"insert [$persistenceId]")(
        connection => bind(connection.createStatement(insertSql), events.head),
        connection => {
          val stmt = connection.createStatement(insertedDbTimestampSql)
          stmt.bind(0, persistenceId)
          stmt.bind(1, highestSeqNr)
        },
        row => row.get(0, classOf[Instant]))
      if (log.isDebugEnabled())
        result.foreach { _ =>
          log.debug("Wrote [{}] events for persistenceId [{}]", 1, events.head.persistenceId)
        }
      result
    } else {
      val result = r2dbcExecutor.updateInBatchThenSelect(s"batch insert [$persistenceId], [$totalEvents] events")(
        connection =>
          events.zipWithIndex.foldLeft(connection.createStatement(insertSql)) { case (stmt, (write, idx)) =>
            if (idx != 0) {
              stmt.add()
            }
            bind(stmt, write)
          },
        connection => {
          val stmt = connection.createStatement(insertedDbTimestampSql)
          stmt.bind(0, persistenceId)
          stmt.bind(1, highestSeqNr)
        },
        row => row.get(0, classOf[Instant]))
      if (log.isDebugEnabled())
        result.foreach { _ =>
          log.debug("Wrote [{}] events for persistenceId [{}]", 1, events.head.persistenceId)
        }
      result
    }
  }

  override def readHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    val result = r2dbcExecutor
      .select(s"select highest seqNr [$persistenceId]")(
        connection =>
          connection
            .createStatement(selectHighestSequenceNrSql)
            .bind(0, persistenceId)
            .bind(1, fromSequenceNr),
        row => {
          val seqNr = row.get[java.lang.Long](0, classOf[java.lang.Long])
          if (seqNr eq null) 0L else seqNr.longValue
        })
      .map(r => if (r.isEmpty) 0L else r.head)(ExecutionContexts.parasitic)

    if (log.isDebugEnabled)
      result.foreach(seqNr => log.debug("Highest sequence nr for persistenceId [{}]: [{}]", persistenceId, seqNr))

    result
  }

  override def deleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    val entityType = PersistenceId.extractEntityType(persistenceId)
    val slice = persistenceExt.sliceForPersistenceId(persistenceId)

    val deleteMarkerSeqNrFut =
      if (toSequenceNr == Long.MaxValue)
        readHighestSequenceNr(persistenceId, 0L)
      else
        Future.successful(toSequenceNr)

    deleteMarkerSeqNrFut.flatMap { deleteMarkerSeqNr =>
      def bindDeleteMarker(stmt: Statement): Statement = {
        stmt
          .bind(0, slice)
          .bind(1, entityType)
          .bind(2, persistenceId)
          .bind(3, deleteMarkerSeqNr)
          .bind(4, "")
          .bind(5, "")
          .bind(6, 0)
          .bind(7, "")
          .bind(8, Array.emptyByteArray)
          .bind(9, true)
      }

      val result = r2dbcExecutor.update(s"delete [$persistenceId]") { connection =>
        Vector(
          connection
            .createStatement(deleteEventsSql)
            .bind(0, persistenceId)
            .bind(1, toSequenceNr),
          bindDeleteMarker(connection.createStatement(insertDeleteMarkerSql)))
      }

      if (log.isDebugEnabled)
        result.foreach(updatedRows =>
          log.debug("Deleted [{}] events for persistenceId [{}]", updatedRows.head, persistenceId))

      result.map(_ => ())(ExecutionContexts.parasitic)
    }
  }
}
