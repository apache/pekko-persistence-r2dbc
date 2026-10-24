/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.snapshot

import java.time.Instant

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.FiniteDuration

import org.apache.pekko
import pekko.NotUsed
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.SnapshotSelectionCriteria
import pekko.persistence.r2dbc.ConnectionFactoryProvider
import pekko.persistence.r2dbc.ConnectionFactorySettings
import pekko.persistence.r2dbc.Dialect
import pekko.persistence.r2dbc.SnapshotSettings
import pekko.persistence.r2dbc.internal.BySliceQuery
import pekko.persistence.r2dbc.internal.BySliceQuery.Buckets
import pekko.persistence.r2dbc.internal.BySliceQuery.Buckets.Bucket
import pekko.persistence.r2dbc.internal.EnvelopeOrigin
import pekko.persistence.r2dbc.internal.InstantFactory
import pekko.persistence.r2dbc.internal.PayloadCodec
import pekko.persistence.r2dbc.internal.PayloadCodec.RichRow
import pekko.persistence.r2dbc.internal.PayloadCodec.RichStatement
import pekko.persistence.r2dbc.internal.R2dbcExecutor
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.snapshot.mariadb.MariaDBSnapshotDao
import pekko.persistence.r2dbc.snapshot.mysql.MySQLSnapshotDao
import pekko.persistence.r2dbc.snapshot.h2.H2SnapshotDao
import pekko.persistence.r2dbc.internal.h2.H2Connections
import pekko.persistence.r2dbc.internal.mariadb.MariaDBConnections
import pekko.persistence.typed.PersistenceId
import pekko.stream.scaladsl.Source
import com.typesafe.config.Config
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Row
import io.r2dbc.spi.Statement
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * INTERNAL API
 */
private[r2dbc] object SnapshotDao {
  private val log: Logger = LoggerFactory.getLogger(classOf[SnapshotDao])

  final case class SerializedSnapshotRow(
      slice: Int,
      entityType: String,
      persistenceId: String,
      seqNr: Long,
      dbTimestamp: Instant,
      writeTimestamp: Long,
      snapshot: Array[Byte],
      serializerId: Int,
      serializerManifest: String,
      tags: Set[String],
      metadata: Option[SerializedSnapshotMetadata])
      extends BySliceQuery.SerializedRow {
    override def readDbTimestamp: Instant = dbTimestamp
    override def source: String = EnvelopeOrigin.SourceQuery
  }

  final case class SerializedSnapshotMetadata(payload: Array[Byte], serializerId: Int, serializerManifest: String)

  private def setFromDb[T](array: Array[T]): Set[T] = array match {
    case null    => Set.empty[T]
    case entries => entries.toSet
  }

  def fromConfig(
      settings: SnapshotSettings,
      config: Config
  )(implicit system: ActorSystem[?], ec: ExecutionContext): SnapshotDao = {
    val connectionFactoryConfig = config.withFallback(system.settings.config).getConfig(settings.useConnectionFactory)
    MariaDBConnections.requireMatchingDialect(settings.dialect, connectionFactoryConfig)
    val connectionFactory =
      ConnectionFactoryProvider(system).connectionFactoryFor(settings.useConnectionFactory, config)
    val closeCallsExceeding = ConnectionFactorySettings.closeCallsExceeding(connectionFactoryConfig)
    settings.dialect match {
      case Dialect.Postgres | Dialect.Yugabyte =>
        new SnapshotDao(settings, connectionFactory, closeCallsExceeding)
      case Dialect.MySQL =>
        new MySQLSnapshotDao(settings, connectionFactory, closeCallsExceeding)
      case Dialect.MariaDB =>
        new MariaDBSnapshotDao(settings, connectionFactory, closeCallsExceeding)
      case Dialect.H2 =>
        val tablesConnectionFactory = H2Connections.creatingTables(
          connectionFactory,
          connectionFactoryConfig,
          _ =>
            H2Connections.schemaDdl(settings.schema) ++
            H2Connections.snapshotTableDdl(settings.snapshotsTableWithSchema) ++
            H2Connections.additionalInit(settings.config))
        new H2SnapshotDao(settings, tablesConnectionFactory, closeCallsExceeding)(
          H2Connections.executionContext(system, connectionFactoryConfig),
          system)
    }
  }
}

/**
 * INTERNAL API
 *
 * Class for doing db interaction outside of an actor to avoid mistakes in future callbacks
 */
@InternalApi
private[r2dbc] class SnapshotDao(settings: SnapshotSettings, connectionFactory: ConnectionFactory,
    closeCallsExceeding: Option[FiniteDuration] = None)(
    implicit
    ec: ExecutionContext,
    system: ActorSystem[?]) extends BySliceQuery.Dao[SnapshotDao.SerializedSnapshotRow] {
  import SnapshotDao._

  implicit protected val dialect: Dialect = settings.dialect

  protected val snapshotTable: String = settings.snapshotsTableWithSchema
  protected val journalTable: String = settings.journalTableWithSchema
  private val r2dbcExecutor =
    new R2dbcExecutor(connectionFactory, log, settings.logDbCallsExceeding, closeCallsExceeding)(ec, system)
  protected implicit val snapshotPayloadCodec: PayloadCodec = settings.snapshotPayloadCodec

  protected def tagsFromRow(row: Row): Set[String] =
    setFromDb(row.get("tags", classOf[Array[String]]))

  protected def bindTagsForWrite(stmt: Statement, tags: Set[String], index: Int): Statement =
    if (tags.isEmpty) stmt.bindNull(index, classOf[Array[String]])
    else stmt.bind(index, tags.toArray)

  protected def sliceCondition(minSlice: Int, maxSlice: Int): String =
    settings.dialect match {
      case Dialect.Postgres => s"slice in (${(minSlice to maxSlice).mkString(",")})"
      case _                => s"slice BETWEEN $minSlice AND $maxSlice"
    }

  private def collectSerializedSnapshot(row: Row): SerializedSnapshotRow = {
    val persistenceId = row.get("persistence_id", classOf[String])
    val writeTimestamp = row.get[java.lang.Long]("write_timestamp", classOf[java.lang.Long])
    // db_timestamp and tags columns are only used when start-from-snapshot is enabled
    val (dbTimestamp, tags) =
      if (settings.startFromSnapshotEnabled) {
        val timestamp = row.get("db_timestamp", classOf[Instant]) match {
          case null => Instant.ofEpochMilli(writeTimestamp)
          case t    => t
        }
        timestamp -> tagsFromRow(row)
      } else
        Instant.ofEpochMilli(writeTimestamp) -> Set.empty[String]

    SerializedSnapshotRow(
      slice = row.get[Integer]("slice", classOf[Integer]),
      entityType = PersistenceId.extractEntityType(persistenceId),
      persistenceId,
      seqNr = row.get[java.lang.Long]("seq_nr", classOf[java.lang.Long]),
      dbTimestamp,
      writeTimestamp,
      snapshot = row.getPayload("snapshot"),
      serializerId = row.get[Integer]("ser_id", classOf[Integer]),
      serializerManifest = row.get("ser_manifest", classOf[String]),
      tags,
      metadata = {
        val metaSerializerId = row.get[Integer]("meta_ser_id", classOf[Integer])
        if (metaSerializerId eq null) None
        else
          Some(
            SerializedSnapshotMetadata(
              row.get("meta_payload", classOf[Array[Byte]]),
              metaSerializerId,
              row.get("meta_ser_manifest", classOf[String])))
      })
  }

  protected def selectColumns: String =
    if (settings.startFromSnapshotEnabled)
      "slice, persistence_id, seq_nr, db_timestamp, write_timestamp, snapshot, ser_id, ser_manifest, tags, meta_payload, meta_ser_id, meta_ser_manifest"
    else
      "slice, persistence_id, seq_nr, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest"

  protected def createUpsertSql: String =
    if (settings.startFromSnapshotEnabled)
      sql"""
        INSERT INTO $snapshotTable
        (slice, entity_type, persistence_id, seq_nr, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest, db_timestamp, tags)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (persistence_id)
        DO UPDATE SET
          seq_nr = excluded.seq_nr,
          db_timestamp = excluded.db_timestamp,
          write_timestamp = excluded.write_timestamp,
          snapshot = excluded.snapshot,
          ser_id = excluded.ser_id,
          tags = excluded.tags,
          ser_manifest = excluded.ser_manifest,
          meta_payload = excluded.meta_payload,
          meta_ser_id = excluded.meta_ser_id,
          meta_ser_manifest = excluded.meta_ser_manifest"""
    else
      sql"""
        INSERT INTO $snapshotTable
        (slice, entity_type, persistence_id, seq_nr, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (persistence_id)
        DO UPDATE SET
          seq_nr = excluded.seq_nr,
          write_timestamp = excluded.write_timestamp,
          snapshot = excluded.snapshot,
          ser_id = excluded.ser_id,
          ser_manifest = excluded.ser_manifest,
          meta_payload = excluded.meta_payload,
          meta_ser_id = excluded.meta_ser_id,
          meta_ser_manifest = excluded.meta_ser_manifest"""

  private lazy val upsertSql = createUpsertSql

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

    sql"""
      SELECT $selectColumns
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

    sql"""
      DELETE FROM $snapshotTable
      WHERE persistence_id = ?
      $maxSeqNrCondition $minSeqNrCondition $maxTimestampCondition $minTimestampCondition"""
  }

  private def snapshotsBySlicesRangeSql(toDbTimestampParam: Boolean, minSlice: Int, maxSlice: Int): String = {
    val toDbTimestampParamCondition = if (toDbTimestampParam) "AND db_timestamp <= ?" else ""
    sql"""
      SELECT $selectColumns
      FROM $snapshotTable
      WHERE entity_type = ?
      AND ${sliceCondition(minSlice, maxSlice)}
      AND db_timestamp >= ? $toDbTimestampParamCondition
      ORDER BY db_timestamp, seq_nr
      LIMIT ?"""
  }

  protected def selectBucketsSql(minSlice: Int, maxSlice: Int): String =
    sql"""
      SELECT extract(EPOCH from db_timestamp)::BIGINT / 10 AS bucket, count(*) AS count
      FROM $snapshotTable
      WHERE entity_type = ?
      AND ${sliceCondition(minSlice, maxSlice)}
      AND db_timestamp >= ? AND db_timestamp <= ?
      GROUP BY bucket ORDER BY bucket LIMIT ?
      """

  private lazy val selectEventTimestampAndTagsSql = sql"""
    SELECT db_timestamp, tags FROM $journalTable
    WHERE persistence_id = ? AND seq_nr = ? AND deleted = false"""

  private val currentDbTimestampSql =
    "SELECT transaction_timestamp() AS db_timestamp"

  def load(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SerializedSnapshotRow]] = {
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
      .map(_.headOption)(ExecutionContext.parasitic)

  }

  def store(serializedRow: SerializedSnapshotRow): Future[Unit] = {
    r2dbcExecutor
      .updateOne(s"upsert snapshot [${serializedRow.persistenceId}], sequence number [${serializedRow.seqNr}]") {
        connection =>
          val statement =
            connection
              .createStatement(upsertSql)
              .bind(0, serializedRow.slice)
              .bind(1, serializedRow.entityType)
              .bind(2, serializedRow.persistenceId)
              .bind(3, serializedRow.seqNr)
              .bind(4, serializedRow.writeTimestamp)
              .bindPayload(5, serializedRow.snapshot)
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

          if (settings.startFromSnapshotEnabled) {
            statement.bind(11, serializedRow.dbTimestamp)
            bindTagsForWrite(statement, serializedRow.tags, 12)
          }

          statement
      }
      .map(_ => ())(ExecutionContext.parasitic)
  }

  def delete(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
    r2dbcExecutor.updateOne(s"delete snapshot [$persistenceId], criteria [$criteria]") { connection =>
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
    }
  }.map(_ => ())(ExecutionContext.parasitic)

  /**
   * The timestamp and tags of the event with the given sequence number, if it exists. Used when storing a snapshot
   * when start-from-snapshot is enabled.
   */
  def eventTimestampAndTags(persistenceId: String, seqNr: Long): Future[Option[(Instant, Set[String])]] =
    r2dbcExecutor.selectOne("select event timestamp and tags")(
      connection =>
        connection
          .createStatement(selectEventTimestampAndTagsSql)
          .bind(0, persistenceId)
          .bind(1, seqNr),
      row => row.get("db_timestamp", classOf[Instant]) -> tagsFromRow(row))

  /**
   * This is used from `BySliceQuery`, i.e. only if start-from-snapshot is enabled
   */
  override def currentDbTimestamp(): Future[Instant] =
    r2dbcExecutor
      .selectOne("select current db timestamp")(
        connection => connection.createStatement(currentDbTimestampSql),
        row => row.get("db_timestamp", classOf[Instant]))
      .map {
        case Some(time) => time
        case None       => throw new IllegalStateException(s"Expected one row for: $currentDbTimestampSql")
      }

  /**
   * This is used from `BySliceQuery`, i.e. only if start-from-snapshot is enabled
   */
  override def rowsBySlices(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      fromTimestamp: Instant,
      toTimestamp: Option[Instant],
      behindCurrentTime: FiniteDuration,
      backtracking: Boolean): Source[SerializedSnapshotRow, NotUsed] = {
    val result = r2dbcExecutor.select(s"select snapshotsBySlices [$minSlice - $maxSlice]")(
      connection => {
        val stmt = connection
          .createStatement(snapshotsBySlicesRangeSql(toTimestamp.isDefined, minSlice, maxSlice))
          .bind(0, entityType)
          .bind(1, fromTimestamp)
        toTimestamp match {
          case Some(until) =>
            stmt.bind(2, until)
            stmt.bind(3, settings.bufferSize)
          case None =>
            stmt.bind(2, settings.bufferSize)
        }
        stmt
      },
      collectSerializedSnapshot)

    if (log.isDebugEnabled)
      result.foreach(rows =>
        log.debug("Read [{}] snapshots from slices [{} - {}]", rows.size: java.lang.Integer,
          minSlice: java.lang.Integer, maxSlice: java.lang.Integer))

    Source.futureSource(result.map(Source(_))).mapMaterializedValue(_ => NotUsed)
  }

  /**
   * Counts for a bucket may become inaccurate when existing snapshots are updated since the timestamp is changed. This
   * is used from `BySliceQuery`, i.e. only if start-from-snapshot is enabled
   */
  override def countBucketsMayChange: Boolean = true

  /**
   * This is used from `BySliceQuery`, i.e. only if start-from-snapshot is enabled
   */
  override def countBuckets(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      fromTimestamp: Instant,
      limit: Int): Future[Seq[Bucket]] = {

    val toTimestamp = {
      val now = InstantFactory.now() // not important to use database time
      if (fromTimestamp == Instant.EPOCH)
        now
      else {
        // max buckets, just to have some upper bound
        val t = fromTimestamp.plusSeconds(Buckets.BucketDurationSeconds * limit + Buckets.BucketDurationSeconds)
        if (t.isAfter(now)) now else t
      }
    }

    val result = r2dbcExecutor.select(s"select snapshot bucket counts [$minSlice - $maxSlice]")(
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
        log.debug("Read [{}] snapshot bucket counts from slices [{} - {}]", rows.size: java.lang.Integer,
          minSlice: java.lang.Integer, maxSlice: java.lang.Integer))

    result
  }

}
