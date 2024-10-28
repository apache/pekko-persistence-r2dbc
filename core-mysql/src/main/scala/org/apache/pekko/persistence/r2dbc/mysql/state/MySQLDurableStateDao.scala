/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.mysql.state

import java.time.Instant

import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import io.r2dbc.spi.Statement
import org.apache.pekko.Done
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.dispatch.ExecutionContexts
import org.apache.pekko.persistence.Persistence
import org.apache.pekko.persistence.r2dbc.R2dbcSettings
import org.apache.pekko.persistence.r2dbc.internal.BySliceQuery.Buckets
import org.apache.pekko.persistence.r2dbc.internal.BySliceQuery.Buckets.Bucket
import org.apache.pekko.persistence.r2dbc.internal.R2dbcExecutor
import org.apache.pekko.persistence.r2dbc.mysql.journal.MySQLJournalDao
import org.apache.pekko.persistence.r2dbc.mysql.state.MySQLDurableStateDao.log
import org.apache.pekko.persistence.r2dbc.state.scaladsl.DurableStateDao
import org.apache.pekko.persistence.r2dbc.state.scaladsl.DurableStateDao.SerializedStateRow
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

object MySQLDurableStateDao {
  val log: Logger = LoggerFactory.getLogger(classOf[MySQLDurableStateDao])
}

class MySQLDurableStateDao(
    settings: R2dbcSettings,
    connectionFactory: ConnectionFactory
)(implicit ec: ExecutionContext, system: ActorSystem[_]) extends DurableStateDao(settings, connectionFactory) {
  MySQLJournalDao.settingRequirements(settings)

  private val persistenceExt = Persistence(system)
  private val r2dbcExecutor = new R2dbcExecutor(connectionFactory, log, settings.logDbCallsExceeding)(ec, system)

  private val stateTable = settings.durableStateTableWithSchema

  private val selectStateSql: String = s"""
    SELECT revision, state_ser_id, state_ser_manifest, state_payload, db_timestamp
    FROM $stateTable WHERE persistence_id = ?"""

  private def selectBucketsSql(minSlice: Int, maxSlice: Int): String = {
    s"""
     SELECT CAST(UNIX_TIMESTAMP(db_timestamp) AS SIGNED) / 10 AS bucket, count(*) AS count
     FROM $stateTable
     WHERE entity_type = ?
     AND slice BETWEEN $minSlice AND $maxSlice
     AND db_timestamp >= ? AND db_timestamp <= ?
     GROUP BY bucket ORDER BY bucket LIMIT ?
     """
  }

  private val insertStateSql: String = s"""
    INSERT INTO $stateTable
    (slice, entity_type, persistence_id, revision, state_ser_id, state_ser_manifest, state_payload, tags, db_timestamp)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""

  private val updateStateSql: String = {
    val revisionCondition =
      if (settings.durableStateAssertSingleWriter) " AND revision = ?"
      else ""

    s"""
      UPDATE $stateTable
      SET revision = ?, state_ser_id = ?, state_ser_manifest = ?, state_payload = ?, tags = ?, db_timestamp = ?
      WHERE persistence_id = ?
      $revisionCondition"""
  }

  private val deleteStateSql: String =
    s"DELETE from $stateTable WHERE persistence_id = ?"

  private val deleteStateWithRevisionSql: String =
    s"DELETE from $stateTable WHERE persistence_id = ? AND revision = ?"

  private val allPersistenceIdsSql =
    s"SELECT persistence_id from $stateTable ORDER BY persistence_id LIMIT ?"

  private val allPersistenceIdsAfterSql =
    s"SELECT persistence_id from $stateTable WHERE persistence_id > ? ORDER BY persistence_id LIMIT ?"

  private def stateBySlicesRangeSql(
      maxDbTimestampParam: Boolean,
      behindCurrentTime: FiniteDuration,
      backtracking: Boolean,
      minSlice: Int,
      maxSlice: Int): String = {

    def maxDbTimestampParamCondition =
      if (maxDbTimestampParam) s"AND db_timestamp < ?" else ""

    def behindCurrentTimeIntervalCondition =
      if (behindCurrentTime > Duration.Zero)
        s"AND db_timestamp < DATE_SUB(NOW(6), INTERVAL '${behindCurrentTime.toMicros}' MICROSECOND)"
      else ""

    val selectColumns =
      if (backtracking)
        "SELECT persistence_id, revision, db_timestamp, NOW(6) AS read_db_timestamp "
      else
        "SELECT persistence_id, revision, db_timestamp, NOW(6) AS read_db_timestamp, state_ser_id, state_ser_manifest, state_payload "

    s"""
      $selectColumns
      FROM $stateTable
      WHERE entity_type = ?
      AND slice BETWEEN $minSlice AND $maxSlice
      AND db_timestamp >= ? $maxDbTimestampParamCondition $behindCurrentTimeIntervalCondition
      ORDER BY db_timestamp, revision
      LIMIT ?"""
  }

  override def readState(persistenceId: String): Future[Option[SerializedStateRow]] = {
    r2dbcExecutor.selectOne(s"select [$persistenceId]")(
      connection =>
        connection
          .createStatement(selectStateSql)
          .bind(0, persistenceId),
      row =>
        SerializedStateRow(
          persistenceId = persistenceId,
          revision = row.get[java.lang.Long]("revision", classOf[java.lang.Long]),
          dbTimestamp = row.get("db_timestamp", classOf[Instant]),
          readDbTimestamp = Instant.EPOCH, // not needed here
          payload = row.get("state_payload", classOf[Array[Byte]]),
          serId = row.get[Integer]("state_ser_id", classOf[Integer]),
          serManifest = row.get("state_ser_manifest", classOf[String]),
          tags = Set.empty // tags not fetched in queries (yet)
        ))
  }

  override def writeState(state: SerializedStateRow): Future[Done] = {
    require(state.revision > 0)

    val entityType = PersistenceId.extractEntityType(state.persistenceId)
    val slice = persistenceExt.sliceForPersistenceId(state.persistenceId)
    val timestamp = Instant.now()

    def bindTags(stmt: Statement, i: Int): Statement = {
      // TODO tags support
      stmt.bindNull(i, classOf[Array[String]])
    }

    val result = {
      if (state.revision == 1) {
        r2dbcExecutor
          .updateOne(s"insert [${state.persistenceId}]") { connection =>
            val stmt = connection
              .createStatement(insertStateSql)
              .bind(0, slice)
              .bind(1, entityType)
              .bind(2, state.persistenceId)
              .bind(3, state.revision)
              .bind(4, state.serId)
              .bind(5, state.serManifest)
              .bind(6, state.payload)
            bindTags(stmt, 7)
            stmt.bind(8, timestamp)
          }
          .recoverWith { case _: R2dbcDataIntegrityViolationException =>
            Future.failed(
              new IllegalStateException(
                s"Insert failed: durable state for persistence id [${state.persistenceId}] already exists"))
          }
      } else {
        val previousRevision = state.revision - 1

        r2dbcExecutor.updateOne(s"update [${state.persistenceId}]") { connection =>
          val stmt = connection
            .createStatement(updateStateSql)
            .bind(0, state.revision)
            .bind(1, state.serId)
            .bind(2, state.serManifest)
            .bind(3, state.payload)
          bindTags(stmt, 4)
          stmt.bind(5, timestamp)

          if (settings.durableStateAssertSingleWriter)
            stmt
              .bind(6, state.persistenceId)
              .bind(7, previousRevision)
          else
            stmt
              .bind(6, state.persistenceId)
        }
      }
    }

    result.map { updatedRows =>
      if (updatedRows != 1)
        throw new IllegalStateException(
          s"Update failed: durable state for persistence id [${state.persistenceId}] could not be updated to revision [${state.revision}]")
      else {
        log.debug("Updated durable state for persistenceId [{}] to revision [{}]", state.persistenceId, state.revision)
        Done
      }
    }
  }

  override def deleteState(persistenceId: String): Future[Done] = {
    val result =
      r2dbcExecutor.updateOne(s"delete [$persistenceId]") { connection =>
        connection
          .createStatement(deleteStateSql)
          .bind(0, persistenceId)
      }

    if (log.isDebugEnabled())
      result.foreach(_ => log.debug("Deleted durable state for persistenceId [{}]", persistenceId))

    result.map(_ => Done)(ExecutionContexts.parasitic)
  }

  override def deleteStateForRevision(persistenceId: String, revision: Long): Future[Long] = {
    val result =
      r2dbcExecutor.updateOne(s"delete [$persistenceId, $revision]") { connection =>
        connection
          .createStatement(deleteStateWithRevisionSql)
          .bind(0, persistenceId)
          .bind(1, revision)
      }

    if (log.isDebugEnabled())
      result.foreach(_ =>
        log.debug("Deleted durable state for persistenceId [{}]; revision [{}]", persistenceId, revision))

    result
  }

  override def currentDbTimestamp(): Future[Instant] = Future.successful(Instant.now())

  override def rowsBySlices(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      fromTimestamp: Instant,
      toTimestamp: Option[Instant],
      behindCurrentTime: FiniteDuration,
      backtracking: Boolean): Source[SerializedStateRow, NotUsed] = {
    val result = r2dbcExecutor.select(s"select stateBySlices [$minSlice - $maxSlice]")(
      connection => {
        val stmt = connection
          .createStatement(
            stateBySlicesRangeSql(
              maxDbTimestampParam = toTimestamp.isDefined,
              behindCurrentTime,
              backtracking,
              minSlice,
              maxSlice))
          .bind(0, entityType)
          .bind(1, fromTimestamp)
        toTimestamp match {
          case Some(until) =>
            stmt.bind(2, until)
            stmt.bind(3, settings.querySettings.bufferSize)
          case None =>
            stmt.bind(2, settings.querySettings.bufferSize)
        }
        stmt
      },
      row =>
        if (backtracking)
          SerializedStateRow(
            persistenceId = row.get("persistence_id", classOf[String]),
            revision = row.get[java.lang.Long]("revision", classOf[java.lang.Long]),
            dbTimestamp = row.get("db_timestamp", classOf[Instant]),
            readDbTimestamp = row.get("read_db_timestamp", classOf[Instant]),
            payload = null, // lazy loaded for backtracking
            serId = 0,
            serManifest = "",
            tags = Set.empty // tags not fetched in queries (yet)
          )
        else
          SerializedStateRow(
            persistenceId = row.get("persistence_id", classOf[String]),
            revision = row.get[java.lang.Long]("revision", classOf[java.lang.Long]),
            dbTimestamp = row.get("db_timestamp", classOf[Instant]),
            readDbTimestamp = row.get("read_db_timestamp", classOf[Instant]),
            payload = row.get("state_payload", classOf[Array[Byte]]),
            serId = row.get[Integer]("state_ser_id", classOf[Integer]),
            serManifest = row.get("state_ser_manifest", classOf[String]),
            tags = Set.empty // tags not fetched in queries (yet)
          ))

    if (log.isDebugEnabled)
      result.foreach(rows =>
        log.debug("Read [{}] durable states from slices [{} - {}]", rows.size: java.lang.Integer,
          minSlice: java.lang.Integer,
          maxSlice: java.lang.Integer))

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

  override def countBuckets(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      fromTimestamp: Instant,
      limit: Int): Future[Seq[Bucket]] = {

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
}
