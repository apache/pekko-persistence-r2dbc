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

package org.apache.pekko.persistence.r2dbc.query.javadsl

import java.time.Instant
import java.util
import java.util.Optional
import java.util.concurrent.CompletionStage

import scala.concurrent.ExecutionContext
import scala.jdk.FutureConverters._
import scala.jdk.OptionConverters._

import org.apache.pekko
import pekko.NotUsed
import pekko.japi.Pair
import pekko.persistence.query.{ EventEnvelope => ClassicEventEnvelope }
import pekko.persistence.query.Offset
import pekko.persistence.query.javadsl._
import pekko.persistence.query.typed.EventEnvelope
import pekko.persistence.query.typed.javadsl.{
  CurrentEventsByPersistenceIdStartingFromSnapshotQuery,
  CurrentEventsByPersistenceIdTypedQuery,
  CurrentEventsBySliceQuery,
  CurrentEventsBySliceStartingFromSnapshotsQuery,
  EventTimestampQuery,
  EventsByPersistenceIdStartingFromSnapshotQuery,
  EventsByPersistenceIdTypedQuery,
  EventsBySliceQuery,
  EventsBySliceStartingFromSnapshotsQuery,
  LoadEventQuery
}
import pekko.persistence.r2dbc.query.scaladsl
import pekko.stream.javadsl.Source

object R2dbcReadJournal {
  val Identifier: String = scaladsl.R2dbcReadJournal.Identifier
}

final class R2dbcReadJournal(delegate: scaladsl.R2dbcReadJournal)
    extends ReadJournal
    with CurrentEventsBySliceQuery
    with EventsBySliceQuery
    with CurrentEventsBySliceStartingFromSnapshotsQuery
    with EventsBySliceStartingFromSnapshotsQuery
    with EventTimestampQuery
    with LoadEventQuery
    with CurrentEventsByPersistenceIdQuery
    with CurrentEventsByPersistenceIdTypedQuery
    with EventsByPersistenceIdQuery
    with EventsByPersistenceIdTypedQuery
    with CurrentPersistenceIdsQuery
    with PagedPersistenceIdsQuery
    with EventsByPersistenceIdStartingFromSnapshotQuery
    with CurrentEventsByPersistenceIdStartingFromSnapshotQuery {

  override def sliceForPersistenceId(persistenceId: String): Int =
    delegate.sliceForPersistenceId(persistenceId)

  override def currentEventsBySlices[Event](
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[EventEnvelope[Event], NotUsed] =
    delegate.currentEventsBySlices(entityType, minSlice, maxSlice, offset).asJava

  override def eventsBySlices[Event](
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[EventEnvelope[Event], NotUsed] =
    delegate.eventsBySlices(entityType, minSlice, maxSlice, offset).asJava

  /**
   * Same as `currentEventsBySlices` but with the purpose to use snapshots as starting points and thereby reducing
   * number of events that have to be loaded. This can be useful if the consumer start from zero without any previously
   * processed offset or if it has been disconnected for a long while and its offset is far behind.
   *
   * First it loads all snapshots with timestamps greater than or equal to the offset timestamp. There is at most one
   * snapshot per persistenceId. The snapshots are transformed to events with the given `transformSnapshot` function.
   *
   * After emitting the snapshot events the ordinary events with sequence numbers after the snapshots are emitted.
   *
   * To use `currentEventsBySlicesStartingFromSnapshots` you must enable configuration
   * `pekko.persistence.r2dbc.query.start-from-snapshot.enabled` and follow the instructions in the migration guide.
   */
  override def currentEventsBySlicesStartingFromSnapshots[Snapshot, Event](
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset,
      transformSnapshot: java.util.function.Function[Snapshot, Event]): Source[EventEnvelope[Event], NotUsed] =
    delegate
      .currentEventsBySlicesStartingFromSnapshots(entityType, minSlice, maxSlice, offset, transformSnapshot(_))
      .asJava

  /**
   * Same as `eventsBySlices` but with the purpose to use snapshots as starting points and thereby reducing number of
   * events that have to be loaded. This can be useful if the consumer start from zero without any previously processed
   * offset or if it has been disconnected for a long while and its offset is far behind.
   *
   * First it loads all snapshots with timestamps greater than or equal to the offset timestamp. There is at most one
   * snapshot per persistenceId. The snapshots are transformed to events with the given `transformSnapshot` function.
   *
   * After emitting the snapshot events the ordinary events with sequence numbers after the snapshots are emitted.
   *
   * To use `eventsBySlicesStartingFromSnapshots` you must enable configuration
   * `pekko.persistence.r2dbc.query.start-from-snapshot.enabled` and follow the instructions in the migration guide.
   */
  override def eventsBySlicesStartingFromSnapshots[Snapshot, Event](
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset,
      transformSnapshot: java.util.function.Function[Snapshot, Event]): Source[EventEnvelope[Event], NotUsed] =
    delegate.eventsBySlicesStartingFromSnapshots(entityType, minSlice, maxSlice, offset, transformSnapshot(_)).asJava

  override def sliceRanges(numberOfRanges: Int): util.List[Pair[Integer, Integer]] = {
    import scala.jdk.CollectionConverters._
    delegate
      .sliceRanges(numberOfRanges)
      .map(range => Pair(Integer.valueOf(range.min), Integer.valueOf(range.max)))
      .asJava
  }

  override def currentEventsByPersistenceId(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[ClassicEventEnvelope, NotUsed] =
    delegate.currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr).asJava

  override def currentEventsByPersistenceIdTyped[Event](
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[EventEnvelope[Event], NotUsed] =
    delegate.currentEventsByPersistenceIdTyped[Event](persistenceId, fromSequenceNr, toSequenceNr).asJava

  override def eventsByPersistenceId(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[ClassicEventEnvelope, NotUsed] =
    delegate.eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr).asJava

  override def eventsByPersistenceIdTyped[Event](
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[EventEnvelope[Event], NotUsed] =
    delegate.eventsByPersistenceIdTyped[Event](persistenceId, fromSequenceNr, toSequenceNr).asJava

  override def currentEventsByPersistenceIdStartingFromSnapshot[Snapshot, Event](
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long,
      transformSnapshot: java.util.function.Function[Snapshot, Event]): Source[EventEnvelope[Event], NotUsed] =
    delegate
      .currentEventsByPersistenceIdStartingFromSnapshot(
        persistenceId,
        fromSequenceNr,
        toSequenceNr,
        transformSnapshot(_))
      .asJava

  override def eventsByPersistenceIdStartingFromSnapshot[Snapshot, Event](
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long,
      transformSnapshot: java.util.function.Function[Snapshot, Event]): Source[EventEnvelope[Event], NotUsed] =
    delegate
      .eventsByPersistenceIdStartingFromSnapshot(persistenceId, fromSequenceNr, toSequenceNr, transformSnapshot(_))
      .asJava

  override def currentPersistenceIds(): Source[String, NotUsed] =
    delegate.currentPersistenceIds().asJava

  override def currentPersistenceIds(afterId: Optional[String], limit: Long): Source[String, NotUsed] =
    delegate.currentPersistenceIds(afterId.toScala, limit).asJava

  /**
   * Get the current persistence ids.
   *
   * Note: to reuse existing index, the actual query filters entity types based on persistence_id column and sql LIKE
   * operator. Hence the persistenceId must start with an entity type followed by default separator ("|") from
   * [[org.apache.pekko.persistence.typed.PersistenceId PersistenceId]].
   *
   * @param entityType
   *   The entity type name.
   * @param afterId
   *   The ID to start returning results from, or empty to return all ids. This should be an id returned from a previous
   *   invocation of this command. Callers should not assume that ids are returned in sorted order.
   * @param limit
   *   The maximum results to return. Use Long.MAX_VALUE to return all results. Must be greater than zero.
   * @return
   *   A source containing all the persistence ids, limited as specified.
   */
  def currentPersistenceIds(entityType: String, afterId: Optional[String], limit: Long): Source[String, NotUsed] =
    delegate.currentPersistenceIds(entityType, afterId.toScala, limit).asJava

  override def timestampOf(persistenceId: String, sequenceNr: Long): CompletionStage[Optional[Instant]] =
    delegate.timestampOf(persistenceId, sequenceNr).map(_.toJava)(ExecutionContext.parasitic).asJava

  override def loadEnvelope[Event](persistenceId: String, sequenceNr: Long): CompletionStage[EventEnvelope[Event]] =
    delegate.loadEnvelope[Event](persistenceId, sequenceNr).asJava

}
