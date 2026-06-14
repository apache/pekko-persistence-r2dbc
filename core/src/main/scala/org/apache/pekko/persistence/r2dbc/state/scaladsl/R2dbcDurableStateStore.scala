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

package org.apache.pekko.persistence.r2dbc.state.scaladsl

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }

import com.typesafe.config.Config
import org.apache.pekko
import pekko.Done
import pekko.NotUsed
import pekko.actor.ExtendedActorSystem
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.persistence.Persistence
import pekko.persistence.query.DeletedDurableState
import pekko.persistence.query.DurableStateChange
import pekko.persistence.query.Offset
import pekko.persistence.query.TimestampOffset
import pekko.persistence.query.UpdatedDurableState
import pekko.persistence.query.scaladsl.DurableStateStorePagedPersistenceIdsQuery
import pekko.persistence.query.typed.scaladsl.DurableStateStoreBySliceQuery
import pekko.persistence.r2dbc.StateSettings
import pekko.persistence.r2dbc.internal.BySliceQuery
import pekko.persistence.r2dbc.internal.ContinuousQuery
import pekko.persistence.r2dbc.state.scaladsl.DurableStateDao.SerializedStateRow
import pekko.persistence.state.scaladsl.DurableStateUpdateStore
import pekko.persistence.state.scaladsl.GetObjectResult
import pekko.serialization.SerializationExtension
import pekko.serialization.Serializers
import pekko.stream.scaladsl.Source
import org.slf4j.LoggerFactory

object R2dbcDurableStateStore {
  val Identifier = "pekko.persistence.r2dbc.state"

  private final case class PersistenceIdsQueryState(
      queryCount: Int,
      rowCount: Int,
      latestPid: String,
      tables: List[String])
}

class R2dbcDurableStateStore[A](system: ExtendedActorSystem, config: Config, cfgPath: String)
    extends DurableStateUpdateStore[A]
    with DurableStateStoreBySliceQuery[A]
    with DurableStateStorePagedPersistenceIdsQuery[A] {
  import R2dbcDurableStateStore.PersistenceIdsQueryState

  private val log = LoggerFactory.getLogger(getClass)
  private val settings = StateSettings(config)

  private implicit val typedSystem: ActorSystem[?] = system.toTyped
  implicit val ec: ExecutionContext = system.dispatcher
  private val serialization = SerializationExtension(system)
  private val persistenceExt = Persistence(system)

  private val stateDao = DurableStateDao.fromConfig(settings, config)

  private val bySlice: BySliceQuery[SerializedStateRow, DurableStateChange[A]] = {
    val createEnvelope: (TimestampOffset, SerializedStateRow) => DurableStateChange[A] = (offset, row) => {
      row.payload match {
        case null =>
          // payload = null => lazy loaded for backtracking (ugly, but not worth changing UpdatedDurableState in Akka)
          new UpdatedDurableState(
            row.persistenceId,
            row.revision,
            null.asInstanceOf[A],
            offset,
            row.dbTimestamp.toEpochMilli)
        case Some(bytes) =>
          val payload = serialization.deserialize(bytes, row.serId, row.serManifest).get.asInstanceOf[A]
          new UpdatedDurableState(row.persistenceId, row.revision, payload, offset, row.dbTimestamp.toEpochMilli)
        case None =>
          new DeletedDurableState(row.persistenceId, row.revision, offset, row.dbTimestamp.toEpochMilli)
      }
    }

    val extractOffset: DurableStateChange[A] => TimestampOffset = env => env.offset.asInstanceOf[TimestampOffset]

    new BySliceQuery(stateDao, createEnvelope, extractOffset, settings, log)(typedSystem.executionContext)
  }

  override def getObject(persistenceId: String): Future[GetObjectResult[A]] = {
    implicit val ec: ExecutionContext = system.dispatcher
    stateDao.readState(persistenceId).map {
      case None                => GetObjectResult(None, 0L)
      case Some(serializedRow) =>
        val payload =
          serializedRow.payload.map { bytes =>
            serialization
              .deserialize(bytes, serializedRow.serId, serializedRow.serManifest)
              .get
              .asInstanceOf[A]
          }
        GetObjectResult(payload, serializedRow.revision)
    }
  }

  /**
   * Insert the value if `revision` is 1, which will fail with `IllegalStateException` if there is already a stored
   * value for the given `persistenceId`. Otherwise update the value, which will fail with `IllegalStateException` if
   * the existing stored `revision` + 1 isn't equal to the given `revision`. This optimistic locking check can be
   * disabled with configuration `assert-single-writer`.
   */
  override def upsertObject(persistenceId: String, revision: Long, value: A, tag: String): Future[Done] = {
    val valueAnyRef = value.asInstanceOf[AnyRef]
    val serialized = serialization.serialize(valueAnyRef).get
    val serializer = serialization.findSerializerFor(valueAnyRef)
    val manifest = Serializers.manifestFor(serializer, valueAnyRef)

    val serializedRow = SerializedStateRow(
      persistenceId,
      revision,
      DurableStateDao.EmptyDbTimestamp,
      DurableStateDao.EmptyDbTimestamp,
      Some(serialized),
      serializer.identifier,
      manifest,
      if (tag.isEmpty) Set.empty else Set(tag))

    stateDao.upsertState(serializedRow, value)
  }

  @deprecated(message = "Use the deleteObject overload with revision instead.", since = "1.0.0")
  override def deleteObject(persistenceId: String): Future[Done] =
    deleteObject(persistenceId, revision = 0)

  /**
   * Delete the value, which will fail with `IllegalStateException` if the existing stored `revision` + 1 isn't equal to
   * the given `revision`. This optimistic locking check can be disabled with configuration `assert-single-writer`. The
   * stored revision for the persistenceId is updated and next call to [[getObject]] will return the revision, but with
   * no value.
   *
   * If the given revision is `0` it will fully delete the value and revision from the database without any optimistic
   * locking check. Next call to [[getObject]] will then return revision 0 and no value.
   */
  override def deleteObject(persistenceId: String, revision: Long): Future[Done] = {
    stateDao.deleteState(persistenceId, revision)
  }

  override def sliceForPersistenceId(persistenceId: String): Int =
    persistenceExt.sliceForPersistenceId(persistenceId)

  override def sliceRanges(numberOfRanges: Int): immutable.Seq[Range] =
    persistenceExt.sliceRanges(numberOfRanges)

  override def currentChangesBySlices(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[DurableStateChange[A], NotUsed] =
    bySlice.currentBySlices("currentChangesBySlices", entityType, minSlice, maxSlice, offset)

  override def changesBySlices(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[DurableStateChange[A], NotUsed] =
    bySlice.liveBySlices("changesBySlices", entityType, minSlice, maxSlice, offset)

  override def currentPersistenceIds(afterId: Option[String], limit: Long): Source[String, NotUsed] =
    stateDao.persistenceIds(afterId, limit)

  /**
   * Get the current persistence ids.
   *
   * Note: to reuse existing index, the actual query filters entity types based on persistence_id column and sql LIKE
   * operator. Hence the persistenceId must start with an entity type followed by default separator ("|") from
   * [[pekko.persistence.typed.PersistenceId]].
   *
   * @param entityType
   *   The entity type name.
   * @param afterId
   *   The ID to start returning results from, or [[None]] to return all ids. This should be an id returned from a
   *   previous invocation of this command. Callers should not assume that ids are returned in sorted order.
   * @param limit
   *   The maximum results to return. Use Long.MaxValue to return all results. Must be greater than zero.
   * @return
   *   A source containing all the persistence ids, limited as specified.
   */
  def currentPersistenceIds(entityType: String, afterId: Option[String], limit: Long): Source[String, NotUsed] =
    stateDao.persistenceIds(entityType, afterId, limit)

  def currentPersistenceIds(): Source[String, NotUsed] = {
    import settings.persistenceIdsBufferSize
    def updateState(state: PersistenceIdsQueryState, pid: String): PersistenceIdsQueryState =
      state.copy(rowCount = state.rowCount + 1, latestPid = pid)

    def nextQuery(state: PersistenceIdsQueryState): (PersistenceIdsQueryState, Option[Source[String, NotUsed]]) = {
      def next(newState: PersistenceIdsQueryState) = {
        val newState2 = newState.copy(rowCount = 0, queryCount = newState.queryCount + 1)

        if (newState.queryCount != 0 && log.isDebugEnabled())
          log.debug(
            "persistenceIds query [{}] after [{}]. Found [{}] rows in previous query.",
            newState.queryCount: java.lang.Integer,
            newState.latestPid,
            newState.rowCount: java.lang.Integer)

        val afterPid = if (newState.latestPid == "") None else Some(newState.latestPid)

        newState2 -> Some(
          stateDao
            .persistenceIdsFromTable(afterPid, persistenceIdsBufferSize, newState.tables.head))
      }

      if (state.queryCount == 0L || state.rowCount >= persistenceIdsBufferSize) {
        next(state)
      } else if (state.tables.tail.nonEmpty) {
        // continue with next custom table
        next(state.copy(tables = state.tables.tail, latestPid = ""))
      } else {
        if (log.isDebugEnabled)
          log.debug(
            "persistenceIds query [{}] completed. Found [{}] rows in previous query.",
            state.queryCount,
            state.rowCount)

        state -> None
      }
    }

    val customTables = settings.durableStateTableByEntityTypeWithSchema.toList.sortBy(_._1).map(_._2)
    val tables = settings.durableStateTableWithSchema :: customTables

    ContinuousQuery[PersistenceIdsQueryState, String](
      initialState = PersistenceIdsQueryState(0, 0, "", tables),
      updateState = updateState,
      delayNextQuery = _ => None,
      nextQuery = state => nextQuery(state))
      .mapMaterializedValue(_ => NotUsed)
  }

}
