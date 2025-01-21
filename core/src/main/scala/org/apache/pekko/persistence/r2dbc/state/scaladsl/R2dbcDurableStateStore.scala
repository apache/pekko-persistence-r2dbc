/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.state.scaladsl

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import com.typesafe.config.Config
import org.apache.pekko
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.persistence.r2dbc.ConnectionFactoryProvider
import org.apache.pekko.persistence.r2dbc.internal.R2dbcExecutor.PublisherOps
import pekko.Done
import pekko.NotUsed
import pekko.actor.ExtendedActorSystem
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.dispatch.ExecutionContexts
import pekko.persistence.Persistence
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

  private final case class PersistenceIdsQueryState(queryCount: Int, rowCount: Int, latestPid: String)
}

class R2dbcDurableStateStore[A](system: ExtendedActorSystem, config: Config, cfgPath: String)
    extends DurableStateUpdateStore[A]
    with DurableStateStoreBySliceQuery[A]
    with DurableStateStorePagedPersistenceIdsQuery[A] {
  import R2dbcDurableStateStore.PersistenceIdsQueryState

  private val log = LoggerFactory.getLogger(getClass)
  private val settings = StateSettings(config)

  private implicit val typedSystem: ActorSystem[_] = system.toTyped
  implicit val ec: ExecutionContext = system.dispatcher
  private val serialization = SerializationExtension(system)
  private val persistenceExt = Persistence(system)

  val connectionFactory =
    ConnectionFactoryProvider(system.toTyped).connectionFactoryFor(settings.shared.connectionFactorySettings)
  CoordinatedShutdown(system)
    .addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "close connection pool") { () =>
      // TODO shared connection factories should not be shutdown
      connectionFactory.disposeLater().asFutureDone()
    }

  private val stateDao = DurableStateDao.fromConfig(settings, connectionFactory)

  private val bySlice: BySliceQuery[SerializedStateRow, DurableStateChange[A]] = {
    val createEnvelope: (TimestampOffset, SerializedStateRow) => DurableStateChange[A] = (offset, row) => {
      val payload = serialization.deserialize(row.payload, row.serId, row.serManifest).get.asInstanceOf[A]
      new UpdatedDurableState(row.persistenceId, row.revision, payload, offset, row.dbTimestamp.toEpochMilli)
    }

    val extractOffset: DurableStateChange[A] => TimestampOffset = env => env.offset.asInstanceOf[TimestampOffset]

    new BySliceQuery(stateDao, createEnvelope, extractOffset, settings.shared, log)(typedSystem.executionContext)
  }

  override def getObject(persistenceId: String): Future[GetObjectResult[A]] = {
    implicit val ec: ExecutionContext = system.dispatcher
    stateDao.readState(persistenceId).map {
      case None => GetObjectResult(None, 0L)
      case Some(serializedRow) =>
        val payload = serialization
          .deserialize(serializedRow.payload, serializedRow.serId, serializedRow.serManifest)
          .get
          .asInstanceOf[A]
        GetObjectResult(Some(payload), serializedRow.revision)
    }
  }

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
      serialized,
      serializer.identifier,
      manifest,
      if (tag.isEmpty) Set.empty else Set(tag))

    stateDao.writeState(serializedRow)

  }
  override def deleteObject(persistenceId: String): Future[Done] =
    stateDao.deleteState(persistenceId)

  override def deleteObject(persistenceId: String, revision: Long): Future[Done] = {
    stateDao.deleteStateForRevision(persistenceId, revision).map { count =>
      if (count != 1) {
        // if you run this code with Pekko 1.0.x, no exception will be thrown here
        // this matches the behavior of pekko-connectors-jdbc 1.0.x
        // if you run this code with Pekko 1.1.x, a DeleteRevisionException will be thrown here
        val msg = if (count == 0) {
          s"Failed to delete object with persistenceId [$persistenceId] and revision [$revision]"
        } else {
          s"Delete object succeeded for persistenceId [$persistenceId] and revision [$revision] but more than one row was affected ($count rows)"
        }
        DurableStateExceptionSupport.createDeleteRevisionExceptionIfSupported(msg)
          .foreach(throw _)
      }
      Done
    }(ExecutionContexts.parasitic)
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

  def currentPersistenceIds(): Source[String, NotUsed] = {
    import settings.shared.persistenceIdsBufferSize
    def updateState(state: PersistenceIdsQueryState, pid: String): PersistenceIdsQueryState =
      state.copy(rowCount = state.rowCount + 1, latestPid = pid)

    def nextQuery(state: PersistenceIdsQueryState): (PersistenceIdsQueryState, Option[Source[String, NotUsed]]) = {
      if (state.queryCount == 0L || state.rowCount >= persistenceIdsBufferSize) {
        val newState = state.copy(rowCount = 0, queryCount = state.queryCount + 1)

        if (state.queryCount != 0 && log.isDebugEnabled())
          log.debug(
            "persistenceIds query [{}] after [{}]. Found [{}] rows in previous query.",
            state.queryCount: java.lang.Integer,
            state.latestPid,
            state.rowCount: java.lang.Integer)

        newState -> Some(
          stateDao
            .persistenceIds(if (state.latestPid == "") None else Some(state.latestPid), persistenceIdsBufferSize))
      } else {
        if (log.isDebugEnabled)
          log.debug(
            "persistenceIds query [{}] completed. Found [{}] rows in previous query.",
            state.queryCount,
            state.rowCount)

        state -> None
      }
    }

    ContinuousQuery[PersistenceIdsQueryState, String](
      initialState = PersistenceIdsQueryState(0, 0, ""),
      updateState = updateState,
      delayNextQuery = _ => None,
      nextQuery = state => nextQuery(state))
      .mapMaterializedValue(_ => NotUsed)
  }

}
