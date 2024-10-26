/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.mysql.query

import java.time.Instant

import io.r2dbc.spi.ConnectionFactory
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.r2dbc.R2dbcSettings
import org.apache.pekko.persistence.r2dbc.internal.BySliceQuery.Buckets
import org.apache.pekko.persistence.r2dbc.internal.R2dbcExecutor
import org.apache.pekko.persistence.r2dbc.internal.Sql.Interpolation
import org.apache.pekko.persistence.r2dbc.journal.JournalDao
import org.apache.pekko.persistence.r2dbc.journal.JournalDao.SerializedJournalRow
import org.apache.pekko.persistence.r2dbc.journal.JournalDao.readMetadata
import org.apache.pekko.persistence.r2dbc.mysql.query.MySQLQueryDao.log
import org.apache.pekko.persistence.r2dbc.query.scaladsl.QueryDao
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

object MySQLQueryDao {
  val log: Logger = LoggerFactory.getLogger(classOf[MySQLQueryDao])
}

class MySQLQueryDao(
    journalSettings: R2dbcSettings,
    connectionFactory: ConnectionFactory
)(implicit ec: ExecutionContext, system: ActorSystem[_]) extends QueryDao(journalSettings, connectionFactory) {

  private val r2dbcExecutor = new R2dbcExecutor(connectionFactory, log, journalSettings.logDbCallsExceeding)

  private val journalTable = journalSettings.journalTableWithSchema

  private val selectEventsSql = s"""
    SELECT slice, entity_type, persistence_id, seq_nr, db_timestamp, NOW() AS read_db_timestamp, event_ser_id, event_ser_manifest, event_payload, writer, adapter_manifest, meta_ser_id, meta_ser_manifest, meta_payload
    FROM $journalTable
    WHERE persistence_id = ? AND seq_nr >= ? AND seq_nr <= ?
    AND deleted = false
    ORDER BY seq_nr
    LIMIT ?"""

  override def currentDbTimestamp(): Future[Instant] = ???

  override def rowsBySlices(entityType: String, minSlice: Int, maxSlice: Int, fromTimestamp: Instant,
      toTimestamp: Option[Instant], behindCurrentTime: FiniteDuration, backtracking: Boolean)
      : Source[JournalDao.SerializedJournalRow, NotUsed] = ???

  override def countBuckets(entityType: String, minSlice: Int, maxSlice: Int, fromTimestamp: Instant, limit: Int)
      : Future[Seq[Buckets.Bucket]] = ???

  override def countBucketsMayChange: Boolean = ???
  override def timestampOfEvent(persistenceId: String, seqNr: Long): Future[Option[Instant]] =
    ???

  override def loadEvent(persistenceId: String, seqNr: Long): Future[Option[JournalDao.SerializedJournalRow]] = ???

  override def eventsByPersistenceId(
      persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long
  ): Source[JournalDao.SerializedJournalRow, NotUsed] = {
    val result = r2dbcExecutor.select(s"select eventsByPersistenceId [$persistenceId]")(
      connection =>
        connection
          .createStatement(selectEventsSql)
          .bind(0, persistenceId)
          .bind(1, fromSequenceNr)
          .bind(2, toSequenceNr)
          .bind(3, journalSettings.querySettings.bufferSize),
      row =>
        SerializedJournalRow(
          slice = row.get[Integer]("slice", classOf[Integer]),
          entityType = row.get("entity_type", classOf[String]),
          persistenceId = row.get("persistence_id", classOf[String]),
          seqNr = row.get[java.lang.Long]("seq_nr", classOf[java.lang.Long]),
          dbTimestamp = row.get("db_timestamp", classOf[Instant]),
          readDbTimestamp = row.get("read_db_timestamp", classOf[Instant]),
          payload = Some(row.get("event_payload", classOf[Array[Byte]])),
          serId = row.get[Integer]("event_ser_id", classOf[Integer]),
          serManifest = row.get("event_ser_manifest", classOf[String]),
          writerUuid = row.get("writer", classOf[String]),
          tags = Set.empty, // tags not fetched in queries (yet)
          metadata = readMetadata(row)))

    if (log.isDebugEnabled)
      result.foreach(rows => log.debug("Read [{}] events for persistenceId [{}]", rows.size, persistenceId))

    Source.futureSource(result.map(Source(_))).mapMaterializedValue(_ => NotUsed)
  }

  override def persistenceIds(afterId: Option[String], limit: Long): Source[String, NotUsed] = ???
}
