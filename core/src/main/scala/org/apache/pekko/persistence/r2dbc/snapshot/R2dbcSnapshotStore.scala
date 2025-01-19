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

package org.apache.pekko.persistence.r2dbc.snapshot

import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.persistence.{ SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria }
import pekko.persistence.r2dbc.SnapshotSettings
import pekko.persistence.snapshot.SnapshotStore
import pekko.serialization.{ Serialization, SerializationExtension }
import com.typesafe.config.Config
import scala.concurrent.{ ExecutionContext, Future }

import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.snapshot.SnapshotDao.SerializedSnapshotMetadata
import pekko.persistence.r2dbc.snapshot.SnapshotDao.SerializedSnapshotRow
import pekko.serialization.Serializers

object R2dbcSnapshotStore {
  private def deserializeSnapshotRow(snap: SerializedSnapshotRow, serialization: Serialization): SelectedSnapshot =
    SelectedSnapshot(
      SnapshotMetadata(
        snap.persistenceId,
        snap.seqNr,
        snap.writeTimestamp,
        snap.metadata.map(serializedMeta =>
          serialization
            .deserialize(serializedMeta.payload, serializedMeta.serializerId, serializedMeta.serializerManifest)
            .get)),
      serialization.deserialize(snap.snapshot, snap.serializerId, snap.serializerManifest).get)
}

/**
 * INTERNAL API
 *
 * Note: differs from other snapshot stores in that in does not retain old snapshots but keeps a single snapshot per
 * entity that is updated.
 */
@InternalApi
private[r2dbc] final class R2dbcSnapshotStore(cfg: Config, cfgPath: String) extends SnapshotStore {
  import R2dbcSnapshotStore.deserializeSnapshotRow

  private implicit val ec: ExecutionContext = context.dispatcher
  private val serialization: Serialization = SerializationExtension(context.system)
  private implicit val system: ActorSystem[_] = context.system.toTyped

  private val dao = {
    val settings = SnapshotSettings(cfg)
    SnapshotDao.fromConfig(settings, cfgPath)
  }

  def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] =
    dao
      .load(persistenceId, criteria)
      .map(_.map(row => deserializeSnapshotRow(row, serialization)))

  def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    val snapshotAnyRef = snapshot.asInstanceOf[AnyRef]
    val serializedSnapshot = serialization.serialize(snapshotAnyRef).get
    val snapshotSerializer = serialization.findSerializerFor(snapshotAnyRef)
    val snapshotManifest = Serializers.manifestFor(snapshotSerializer, snapshotAnyRef)

    val serializedMeta: Option[SerializedSnapshotMetadata] = metadata.metadata.map { meta =>
      val metaRef = meta.asInstanceOf[AnyRef]
      val serializedMeta = serialization.serialize(metaRef).get
      val metaSerializer = serialization.findSerializerFor(metaRef)
      val metaManifest = Serializers.manifestFor(metaSerializer, metaRef)
      SerializedSnapshotMetadata(serializedMeta, metaSerializer.identifier, metaManifest)
    }

    val serializedRow = SerializedSnapshotRow(
      metadata.persistenceId,
      metadata.sequenceNr,
      metadata.timestamp,
      serializedSnapshot,
      snapshotSerializer.identifier,
      snapshotManifest,
      serializedMeta)

    dao.store(serializedRow)
  }

  def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = {
    val criteria =
      if (metadata.timestamp == 0L)
        SnapshotSelectionCriteria(maxSequenceNr = metadata.sequenceNr, minSequenceNr = metadata.sequenceNr)
      else
        SnapshotSelectionCriteria(
          maxSequenceNr = metadata.sequenceNr,
          minSequenceNr = metadata.sequenceNr,
          maxTimestamp = metadata.timestamp,
          minTimestamp = metadata.timestamp)
    deleteAsync(metadata.persistenceId, criteria)
  }

  def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] =
    dao.delete(persistenceId, criteria)
}
