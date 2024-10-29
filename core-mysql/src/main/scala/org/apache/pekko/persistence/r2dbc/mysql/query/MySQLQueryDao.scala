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
import org.apache.pekko.persistence.r2dbc.internal.BySliceQuery.Buckets.Bucket
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
import scala.concurrent.duration.Duration
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

  private def eventsBySlicesRangeSql(
      toDbTimestampParam: Boolean,
      behindCurrentTime: FiniteDuration,
      backtracking: Boolean,
      minSlice: Int,
      maxSlice: Int): String = {

    def toDbTimestampParamCondition =
      if (toDbTimestampParam) "AND db_timestamp <= ?" else ""

    def behindCurrentTimeIntervalCondition =
      if (behindCurrentTime > Duration.Zero)
        s"AND db_timestamp < DATE_SUB(NOW(6), INTERVAL '${behindCurrentTime.toMicros}' MICROSECOND)"
      else ""

    val selectColumns = {
      if (backtracking)
        "SELECT slice, persistence_id, seq_nr, db_timestamp, NOW(6) AS read_db_timestamp "
      else
        "SELECT slice, persistence_id, seq_nr, db_timestamp, NOW(6) AS read_db_timestamp, event_ser_id, event_ser_manifest, event_payload, meta_ser_id, meta_ser_manifest, meta_payload "
    }

    s"""
      $selectColumns
      FROM $journalTable
      WHERE entity_type = ?
      AND slice BETWEEN $minSlice AND $maxSlice
      AND db_timestamp >= ? $toDbTimestampParamCondition $behindCurrentTimeIntervalCondition
      AND deleted = false
      ORDER BY db_timestamp, seq_nr
      LIMIT ?"""
  }

  private def selectBucketsSql(minSlice: Int, maxSlice: Int): String = {
    s"""
      SELECT CAST(UNIX_TIMESTAMP(db_timestamp) AS SIGNED) / 10 AS bucket, count(*) AS count
      FROM $journalTable
      WHERE entity_type = ?
      AND slice BETWEEN $minSlice AND $maxSlice
      AND db_timestamp >= ? AND db_timestamp <= ?
      AND deleted = false
      GROUP BY bucket ORDER BY bucket LIMIT ?
      """
  }

  private val selectTimestampOfEventSql = s"""
    SELECT db_timestamp FROM $journalTable
    WHERE persistence_id = ? AND seq_nr = ? AND deleted = false"""

  private val selectOneEventSql = s"""
    SELECT slice, entity_type, db_timestamp, NOW(6) AS read_db_timestamp, event_ser_id, event_ser_manifest, event_payload, meta_ser_id, meta_ser_manifest, meta_payload
    FROM $journalTable
    WHERE persistence_id = ? AND seq_nr = ? AND deleted = false"""

  private val selectEventsSql = s"""
    SELECT slice, entity_type, persistence_id, seq_nr, db_timestamp, NOW(6) AS read_db_timestamp, event_ser_id, event_ser_manifest, event_payload, writer, adapter_manifest, meta_ser_id, meta_ser_manifest, meta_payload
    FROM $journalTable
    WHERE persistence_id = ? AND seq_nr >= ? AND seq_nr <= ?
    AND deleted = false
    ORDER BY seq_nr
    LIMIT ?"""

  private val allPersistenceIdsSql =
    s"SELECT DISTINCT(persistence_id) from $journalTable ORDER BY persistence_id LIMIT ?"

  private val allPersistenceIdsAfterSql =
    s"SELECT DISTINCT(persistence_id) from $journalTable WHERE persistence_id > ? ORDER BY persistence_id LIMIT ?"

  override def currentDbTimestamp(): Future[Instant] = Future.successful(Instant.now())

  override def rowsBySlices(entityType: String, minSlice: Int, maxSlice: Int, fromTimestamp: Instant,
      toTimestamp: Option[Instant], behindCurrentTime: FiniteDuration, backtracking: Boolean)
      : Source[JournalDao.SerializedJournalRow, NotUsed] = {
    val result = r2dbcExecutor.select(s"select eventsBySlices [$minSlice - $maxSlice]")(
      connection => {
        val stmt = connection
          .createStatement(
            eventsBySlicesRangeSql(
              toDbTimestampParam = toTimestamp.isDefined,
              behindCurrentTime,
              backtracking,
              minSlice,
              maxSlice))
          .bind(0, entityType)
          .bind(1, fromTimestamp)
        toTimestamp match {
          case Some(until) =>
            stmt.bind(2, until)
            stmt.bind(3, journalSettings.querySettings.bufferSize)
          case None =>
            stmt.bind(2, journalSettings.querySettings.bufferSize)
        }
        stmt
      },
      row =>
        if (backtracking)
          SerializedJournalRow(
            slice = row.get[Integer]("slice", classOf[Integer]),
            entityType,
            persistenceId = row.get("persistence_id", classOf[String]),
            seqNr = row.get[java.lang.Long]("seq_nr", classOf[java.lang.Long]),
            dbTimestamp = row.get("db_timestamp", classOf[Instant]),
            readDbTimestamp = row.get("read_db_timestamp", classOf[Instant]),
            payload = None, // lazy loaded for backtracking
            serId = 0,
            serManifest = "",
            writerUuid = "", // not need in this query
            tags = Set.empty, // tags not fetched in queries (yet)
            metadata = None)
        else
          SerializedJournalRow(
            slice = row.get[Integer]("slice", classOf[Integer]),
            entityType,
            persistenceId = row.get("persistence_id", classOf[String]),
            seqNr = row.get[java.lang.Long]("seq_nr", classOf[java.lang.Long]),
            dbTimestamp = row.get("db_timestamp", classOf[Instant]),
            readDbTimestamp = row.get("read_db_timestamp", classOf[Instant]),
            payload = Some(row.get("event_payload", classOf[Array[Byte]])),
            serId = row.get[Integer]("event_ser_id", classOf[Integer]),
            serManifest = row.get("event_ser_manifest", classOf[String]),
            writerUuid = "", // not need in this query
            tags = Set.empty, // tags not fetched in queries (yet)
            metadata = readMetadata(row)))

    if (log.isDebugEnabled)
      result.foreach(rows =>
        log.debug("Read [{}] events from slices [{} - {}]", rows.size: java.lang.Integer, minSlice: java.lang.Integer,
          maxSlice: java.lang.Integer))

    Source.futureSource(result.map(Source(_))).mapMaterializedValue(_ => NotUsed)
  }

  override def countBuckets(
      entityType: String, minSlice: Int, maxSlice: Int, fromTimestamp: Instant, limit: Int
  ): Future[Seq[Buckets.Bucket]] = {
    val toTimestamp = {
      val now = Instant.now() // not important to use database time
      if (fromTimestamp == Instant.EPOCH)
        now
      else {
        // max buckets, just to have some upper bound
        val t = fromTimestamp.plusSeconds(Buckets.BucketDurationSeconds * limit + Buckets.BucketDurationSeconds)
        if (t.isAfter(now)) now else t
      }
    }

    val result = r2dbcExecutor.select(s"select bucket counts [$minSlice - $maxSlice]")(
      connection =>
        connection
          .createStatement(selectBucketsSql(minSlice, maxSlice))
          .bind(0, entityType)
          .bind(1, fromTimestamp)
          .bind(2, toTimestamp)
          .bind(3, limit),
      row => {
        val bucketStartEpochSeconds = row.get[java.lang.Long]("bucket", classOf[java.lang.Long]) * 10
        val count = row.get[java.lang.Long]("count", classOf[java.lang.Long])
        Bucket(bucketStartEpochSeconds, count)
      })

    if (log.isDebugEnabled)
      result.foreach(rows =>
        log.debug("Read [{}] bucket counts from slices [{} - {}]", rows.size: java.lang.Integer,
          minSlice: java.lang.Integer,
          maxSlice: java.lang.Integer))

    result
  }

  override def timestampOfEvent(persistenceId: String, seqNr: Long): Future[Option[Instant]] = {
    r2dbcExecutor.selectOne("select timestampOfEvent")(
      connection =>
        connection
          .createStatement(selectTimestampOfEventSql)
          .bind(0, persistenceId)
          .bind(1, seqNr),
      row => row.get("db_timestamp", classOf[Instant]))
  }

  override def loadEvent(persistenceId: String, seqNr: Long): Future[Option[JournalDao.SerializedJournalRow]] =
    r2dbcExecutor.selectOne("select one event")(
      connection =>
        connection
          .createStatement(selectOneEventSql)
          .bind(0, persistenceId)
          .bind(1, seqNr),
      row =>
        SerializedJournalRow(
          slice = row.get[Integer]("slice", classOf[Integer]),
          entityType = row.get("entity_type", classOf[String]),
          persistenceId,
          seqNr,
          dbTimestamp = row.get("db_timestamp", classOf[Instant]),
          readDbTimestamp = row.get("read_db_timestamp", classOf[Instant]),
          payload = Some(row.get("event_payload", classOf[Array[Byte]])),
          serId = row.get[Integer]("event_ser_id", classOf[Integer]),
          serManifest = row.get("event_ser_manifest", classOf[String]),
          writerUuid = "", // not need in this query
          tags = Set.empty, // tags not fetched in queries (yet)
          metadata = readMetadata(row)))

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

  override def persistenceIds(afterId: Option[String], limit: Long): Source[String, NotUsed] = {
    val result = r2dbcExecutor.select(s"select persistenceIds")(
      connection =>
        afterId match {
          case Some(after) =>
            connection
              .createStatement(allPersistenceIdsAfterSql)
              .bind(0, after)
              .bind(1, limit)
          case None =>
            connection
              .createStatement(allPersistenceIdsSql)
              .bind(0, limit)
        },
      row => row.get("persistence_id", classOf[String]))

    if (log.isDebugEnabled)
      result.foreach(rows => log.debug("Read [{}] persistence ids", rows.size))

    Source.futureSource(result.map(Source(_))).mapMaterializedValue(_ => NotUsed)
  }
}
