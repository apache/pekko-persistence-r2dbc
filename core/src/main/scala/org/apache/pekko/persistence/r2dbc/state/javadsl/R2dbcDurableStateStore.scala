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

package org.apache.pekko.persistence.r2dbc.state.javadsl

import java.util
import java.util.Optional
import java.util.concurrent.CompletionStage

import scala.concurrent.ExecutionContext
import scala.jdk.FutureConverters._

import org.apache.pekko
import pekko.Done
import pekko.NotUsed
import pekko.japi.Pair
import pekko.persistence.query.DurableStateChange
import pekko.persistence.query.Offset
import pekko.persistence.query.javadsl.DurableStateStorePagedPersistenceIdsQuery
import pekko.persistence.query.typed.javadsl.DurableStateStoreBySliceQuery
import pekko.persistence.r2dbc.state.scaladsl.{ R2dbcDurableStateStore => ScalaR2dbcDurableStateStore }
import pekko.persistence.state.javadsl.DurableStateUpdateStore
import pekko.persistence.state.javadsl.GetObjectResult
import pekko.stream.javadsl.Source

object R2dbcDurableStateStore {
  val Identifier: String = ScalaR2dbcDurableStateStore.Identifier
}

class R2dbcDurableStateStore[A](scalaStore: ScalaR2dbcDurableStateStore[A])(implicit ec: ExecutionContext)
    extends DurableStateUpdateStore[A]
    with DurableStateStoreBySliceQuery[A]
    with DurableStateStorePagedPersistenceIdsQuery[A] {

  override def getObject(persistenceId: String): CompletionStage[GetObjectResult[A]] =
    scalaStore
      .getObject(persistenceId)
      .map(x => GetObjectResult(Optional.ofNullable(x.value.getOrElse(null.asInstanceOf[A])), x.revision))
      .asJava

  override def upsertObject(persistenceId: String, revision: Long, value: A, tag: String): CompletionStage[Done] =
    scalaStore.upsertObject(persistenceId, revision, value, tag).asJava

  override def deleteObject(persistenceId: String): CompletionStage[Done] =
    scalaStore.deleteObject(persistenceId).asJava

  override def deleteObject(persistenceId: String, revision: Long): CompletionStage[Done] =
    scalaStore.deleteObject(persistenceId, revision).asJava

  override def currentChangesBySlices(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[DurableStateChange[A], NotUsed] =
    scalaStore.currentChangesBySlices(entityType, minSlice, maxSlice, offset).asJava

  override def changesBySlices(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[DurableStateChange[A], NotUsed] =
    scalaStore.changesBySlices(entityType, minSlice, maxSlice, offset).asJava

  override def sliceForPersistenceId(persistenceId: String): Int =
    scalaStore.sliceForPersistenceId(persistenceId)

  override def sliceRanges(numberOfRanges: Int): util.List[Pair[Integer, Integer]] = {
    import scala.jdk.CollectionConverters._
    scalaStore
      .sliceRanges(numberOfRanges)
      .map(range => Pair(Integer.valueOf(range.min), Integer.valueOf(range.max)))
      .asJava
  }

  override def currentPersistenceIds(afterId: Optional[String], limit: Long): Source[String, NotUsed] = {
    import scala.jdk.OptionConverters._
    scalaStore.currentPersistenceIds(afterId.toScala, limit).asJava
  }

  def currentPersistenceIds(): Source[String, NotUsed] =
    scalaStore.currentPersistenceIds().asJava
}
