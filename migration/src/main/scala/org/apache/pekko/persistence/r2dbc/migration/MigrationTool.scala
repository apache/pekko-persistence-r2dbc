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

package org.apache.pekko.persistence.r2dbc.migration

import java.time.Instant

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import org.apache.pekko
import pekko.Done
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.Behavior
import pekko.actor.typed.scaladsl.Behaviors
import pekko.dispatch.ExecutionContexts
import pekko.pattern.ask
import pekko.persistence.Persistence
import pekko.persistence.SelectedSnapshot
import pekko.persistence.SnapshotProtocol.LoadSnapshot
import pekko.persistence.SnapshotProtocol.LoadSnapshotResult
import pekko.persistence.SnapshotSelectionCriteria
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.query.scaladsl.CurrentEventsByPersistenceIdQuery
import pekko.persistence.query.scaladsl.CurrentPersistenceIdsQuery
import pekko.persistence.query.scaladsl.ReadJournal
import pekko.persistence.query.{ EventEnvelope => ClassicEventEnvelope }
import pekko.persistence.r2dbc.ConnectionFactoryProvider
import pekko.persistence.r2dbc.JournalSettings
import pekko.persistence.r2dbc.SnapshotSettings
import pekko.persistence.r2dbc.journal.JournalDao
import pekko.persistence.r2dbc.journal.JournalDao.SerializedEventMetadata
import pekko.persistence.r2dbc.journal.JournalDao.SerializedJournalRow
import pekko.persistence.r2dbc.migration.MigrationToolDao.CurrentProgress
import pekko.persistence.r2dbc.snapshot.SnapshotDao
import pekko.persistence.r2dbc.snapshot.SnapshotDao.SerializedSnapshotMetadata
import pekko.persistence.r2dbc.snapshot.SnapshotDao.SerializedSnapshotRow
import pekko.persistence.typed.PersistenceId
import pekko.serialization.Serialization
import pekko.serialization.SerializationExtension
import pekko.serialization.Serializers
import pekko.stream.scaladsl.Sink
import pekko.util.Timeout
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import org.slf4j.LoggerFactory

object MigrationTool {
  def main(args: Array[String]): Unit = {
    ActorSystem(MigrationTool(), "MigrationTool")
  }

  object Result {
    val empty: Result = Result(0, 0, 0)
  }

  final case class Result(persistenceIds: Long, events: Long, snapshots: Long)

  private def apply(): Behavior[Try[Result]] = {
    Behaviors.setup { context =>
      val migration = new MigrationTool(context.system)
      context.pipeToSelf(migration.migrateAll()) { result =>
        result
      }

      Behaviors.receiveMessage {
        case Success(_) =>
          // result already logged by migrateAll
          Behaviors.stopped
        case Failure(_) =>
          Behaviors.stopped
      }
    }
  }

}

/**
 * Migration from another Pekko Persistence plugin to the R2DBC plugin. Converts events and snapshots. It has been tested
 * with `pekko-persistence-jdbc` as source plugin, but it should work with any plugin that has support for
 * `CurrentPersistenceIdsQuery` and `CurrentEventsByPersistenceIdQuery`.
 *
 * It can be run as a main class `org.apache.pekko.persistence.r2dbc.migration.MigrationTool` with configuration in
 * `application.conf` or embedded in an application by creating an instance of `MigrationTool` and invoking
 * `migrateAll`.
 *
 * It can be run while the source system is still active and it can be run multiple times with idempotent result. To
 * speed up processing of subsequent runs it stores migrated persistence ids and sequence numbers in the table
 * `migration_progress`. In a subsequent run it will only migrate new events and snapshots compared to what was stored
 * in `migration_progress`. It will also find and migrate new persistence ids in a subsequent run. You can delete from
 * `migration_progress` if you want to re-run the full migration.
 *
 * Note: tags are not migrated.
 */
class MigrationTool(system: ActorSystem[_]) {
  import MigrationTool.Result
  import system.executionContext
  private implicit val sys: ActorSystem[_] = system

  private val log = LoggerFactory.getLogger(getClass)

  private val persistenceExt = Persistence(system)

  private val migrationConfig = system.settings.config.getConfig("pekko.persistence.r2dbc.migration")

  private val parallelism = migrationConfig.getInt("parallelism")

  private val targetPluginId = migrationConfig.getString("target.persistence-plugin-id")
  private val targetConfig = system.settings.config.getConfig(targetPluginId)
  private val targetJournalSettings = JournalSettings(targetConfig.getConfig("journal"))
  private val targetSnapshotettings = SnapshotSettings(targetConfig.getConfig("snapshot"))

  private val serialization: Serialization = SerializationExtension(system)

  private val targetJournalConnectionFactory = ConnectionFactoryProvider(system)
    .connectionFactoryFor(targetJournalSettings.useConnectionFactory)
  private val targetJournalDao =
    new JournalDao(targetJournalSettings, targetJournalConnectionFactory)
  private val targetSnapshotDao =
    new SnapshotDao(targetSnapshotettings,
      ConnectionFactoryProvider(system)
        .connectionFactoryFor(targetSnapshotettings.useConnectionFactory))

  private val targetBatch = migrationConfig.getInt("target.batch")

  private val sourceQueryPluginId = migrationConfig.getString("source.query-plugin-id")
  private val sourceReadJournal = PersistenceQuery(system).readJournalFor[ReadJournal](sourceQueryPluginId)
  private val sourcePersistenceIdsQuery = sourceReadJournal.asInstanceOf[CurrentPersistenceIdsQuery]
  private val sourceEventsByPersistenceIdQuery = sourceReadJournal.asInstanceOf[CurrentEventsByPersistenceIdQuery]

  private val sourceSnapshotPluginId = migrationConfig.getString("source.snapshot-plugin-id")
  private lazy val sourceSnapshotStore = Persistence(system).snapshotStoreFor(sourceSnapshotPluginId)

  private[r2dbc] val migrationDao =
    new MigrationToolDao(targetJournalConnectionFactory, targetJournalSettings.logDbCallsExceeding)

  private lazy val createProgressTable: Future[Done] =
    migrationDao.createProgressTable()

  /**
   * Migrates events and snapshots for all persistence ids.
   * @return
   */
  def migrateAll(): Future[Result] = {
    log.info("Migration started.")
    val result =
      sourcePersistenceIdsQuery
        .currentPersistenceIds()
        .mapAsyncUnordered(parallelism) { persistenceId =>
          for {
            _ <- createProgressTable
            currentProgress <- migrationDao.currentProgress(persistenceId)
            eventCount <- migrateEvents(persistenceId, currentProgress)
            snapshotCount <- migrateSnapshot(persistenceId, currentProgress)
          } yield persistenceId -> Result(1, eventCount, snapshotCount)
        }
        .map { case (pid, result @ Result(_, events, snapshots)) =>
          log.debug(
            "Migrated persistenceId [{}] with [{}] events{}.",
            pid,
            events: java.lang.Long,
            if (snapshots == 0) "" else " and snapshot")
          result
        }
        .runWith(Sink.fold(Result.empty) { case (acc: Result, Result(_, events, snapshots)) =>
          val result = Result(acc.persistenceIds + 1, acc.events + events, acc.snapshots + snapshots)
          if (result.persistenceIds % 100 == 0)
            log.info(
              "Migrated [{}] persistenceIds with [{}] events and [{}] snapshots.",
              result.persistenceIds: java.lang.Long,
              result.events: java.lang.Long,
              result.snapshots: java.lang.Long)
          result
        })

    result.transform {
      case s @ Success(Result(persistenceIds, events, snapshots)) =>
        log.info(
          "Migration successful. Migrated [{}] persistenceIds with [{}] events and [{}] snapshots.",
          persistenceIds: java.lang.Long,
          events: java.lang.Long,
          snapshots: java.lang.Long)
        s
      case f @ Failure(exc) =>
        log.error("Migration failed.", exc)
        f
    }
  }

  /**
   * Migrate events for a single persistence id.
   */
  def migrateEvents(persistenceId: String): Future[Long] = {
    for {
      _ <- createProgressTable
      currentProgress <- migrationDao.currentProgress(persistenceId)
      eventCount <- migrateEvents(persistenceId, currentProgress)
    } yield eventCount
  }

  private def migrateEvents(persistenceId: String, currentProgress: Option[CurrentProgress]): Future[Long] = {
    val progressSeqNr = currentProgress.map(_.eventSeqNr).getOrElse(0L)
    sourceEventsByPersistenceIdQuery
      .currentEventsByPersistenceId(persistenceId, progressSeqNr + 1, Long.MaxValue)
      .map(serializedJournalRow)
      .grouped(targetBatch)
      .mapAsync(1) { events =>
        targetJournalDao
          .writeEvents(events)
          .recoverWith { case _: R2dbcDataIntegrityViolationException =>
            // events already exists, which is ok, but since the batch
            // failed we must try again one-by-one
            Future.sequence(events.map { event =>
              targetJournalDao
                .writeEvents(List(event))
                .recoverWith { case _: R2dbcDataIntegrityViolationException =>
                  // ok, already exists
                  log
                    .debug("event already exists, persistenceId [{}], seqNr [{}]", event.persistenceId, event.seqNr)
                  Future.successful(())
                }
            })
          }
          .map(_ => events.last.seqNr -> events.size)
      }
      .mapAsync(1) { case (seqNr, count) =>
        migrationDao
          .updateEventProgress(persistenceId, seqNr)
          .map(_ => count)
      }
      .runWith(Sink.fold(0L) { case (acc, count) => acc + count })
  }

  private def serializedJournalRow(env: ClassicEventEnvelope): SerializedJournalRow = {
    val entityType = PersistenceId.extractEntityType(env.persistenceId)
    val slice = persistenceExt.sliceForPersistenceId(env.persistenceId)

    val event = env.event.asInstanceOf[AnyRef]
    val serialized = serialization.serialize(event).get
    val serializer = serialization.findSerializerFor(event)
    val manifest = Serializers.manifestFor(serializer, event)

    val metadata =
      env.eventMetadata.map { meta =>
        val m = meta.asInstanceOf[AnyRef]
        val serializedMeta = serialization.serialize(m).get
        val metaSerializer = serialization.findSerializerFor(m)
        val metaManifest = Serializers.manifestFor(metaSerializer, m)
        SerializedEventMetadata(metaSerializer.identifier, metaManifest, serializedMeta)
      }

    SerializedJournalRow(
      slice,
      entityType,
      env.persistenceId,
      env.sequenceNr,
      Instant.ofEpochMilli(env.timestamp),
      JournalDao.EmptyDbTimestamp,
      Some(serialized),
      serializer.identifier,
      manifest,
      "", // writerUuid is discarded, but that is ok
      tags = Set.empty, // tags are not migrated (not included in currentEventsByPersistenceId envelope)
      metadata)
  }

  /**
   * Migrate latest snapshot for a single persistence id.
   */
  def migrateSnapshot(persistenceId: String): Future[Int] = {
    for {
      _ <- createProgressTable
      currentProgress <- migrationDao.currentProgress(persistenceId)
      snapCount <- migrateSnapshot(persistenceId, currentProgress)
    } yield snapCount
  }

  private def migrateSnapshot(persistenceId: String, currentProgress: Option[CurrentProgress]): Future[Int] = {
    val progressSeqNr = currentProgress.map(_.snapshotSeqNr).getOrElse(0L)
    loadSourceSnapshot(persistenceId, progressSeqNr + 1).flatMap {
      case None => Future.successful(0)
      case Some(selectedSnapshot @ SelectedSnapshot(snapshotMetadata, _)) =>
        for {
          seqNr <- {
            val serializedRow = serializedSnapotRow(selectedSnapshot)
            targetSnapshotDao
              .store(serializedRow)
              .map(_ => snapshotMetadata.sequenceNr)(ExecutionContexts.parasitic)
          }
          _ <- migrationDao.updateSnapshotProgress(persistenceId, seqNr)
        } yield 1
    }
  }

  private def serializedSnapotRow(selectedSnapshot: SelectedSnapshot): SerializedSnapshotRow = {
    val snapshotMetadata = selectedSnapshot.metadata
    val snapshotAnyRef = selectedSnapshot.snapshot.asInstanceOf[AnyRef]
    val serializedSnapshot = serialization.serialize(snapshotAnyRef).get
    val snapshotSerializer = serialization.findSerializerFor(snapshotAnyRef)
    val snapshotManifest = Serializers.manifestFor(snapshotSerializer, snapshotAnyRef)

    val serializedMeta: Option[SerializedSnapshotMetadata] = snapshotMetadata.metadata.map { meta =>
      val metaRef = meta.asInstanceOf[AnyRef]
      val serializedMeta = serialization.serialize(metaRef).get
      val metaSerializer = serialization.findSerializerFor(metaRef)
      val metaManifest = Serializers.manifestFor(metaSerializer, metaRef)
      SerializedSnapshotMetadata(serializedMeta, metaSerializer.identifier, metaManifest)
    }

    val serializedRow = SerializedSnapshotRow(
      snapshotMetadata.persistenceId,
      snapshotMetadata.sequenceNr,
      snapshotMetadata.timestamp,
      serializedSnapshot,
      snapshotSerializer.identifier,
      snapshotManifest,
      serializedMeta)
    serializedRow
  }

  private def loadSourceSnapshot(persistenceId: String, minSequenceNr: Long): Future[Option[SelectedSnapshot]] = {
    if (sourceSnapshotPluginId == "")
      Future.successful(None)
    else {
      implicit val timeout: Timeout = 10.seconds
      val criteria = SnapshotSelectionCriteria.Latest
      (sourceSnapshotStore ? LoadSnapshot(persistenceId, criteria, Long.MaxValue))
        .mapTo[LoadSnapshotResult]
        .map(result => result.snapshot.flatMap(s => if (s.metadata.sequenceNr >= minSequenceNr) Some(s) else None))
    }

  }

}
