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

package org.apache.pekko.persistence.r2dbc.internal

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

import org.apache.pekko
import pekko.actor.typed.ActorRef
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.Extension
import pekko.actor.typed.ExtensionId
import pekko.actor.typed.pubsub.Topic
import pekko.annotation.InternalApi
import pekko.persistence.Persistence
import pekko.persistence.PersistentRepr
import pekko.persistence.journal.Tagged
import pekko.persistence.query.TimestampOffset
import pekko.persistence.query.typed.EventEnvelope
import pekko.persistence.typed.PersistenceId

/**
 * INTERNAL API
 */
@InternalApi private[pekko] object PubSub extends ExtensionId[PubSub] {
  def createExtension(system: ActorSystem[_]): PubSub = new PubSub(system)

  // Java API
  def get(system: ActorSystem[_]): PubSub = apply(system)

}

/**
 * INTERNAL API
 */
@InternalApi private[pekko] class PubSub(system: ActorSystem[_]) extends Extension {
  private val topics = new ConcurrentHashMap[String, ActorRef[Any]]
  private val persistenceExt = Persistence(system)

  def eventTopic[Event](entityType: String, slice: Int): ActorRef[Topic.Command[EventEnvelope[Event]]] = {
    val name = topicName(entityType, slice)
    topics
      .computeIfAbsent(name, _ => system.systemActorOf(Topic[EventEnvelope[Event]](name), name).unsafeUpcast[Any])
      .narrow[Topic.Command[EventEnvelope[Event]]]
  }

  private def topicName(entityType: String, slice: Int): String =
    URLEncoder.encode(s"r2dbc-$entityType-$slice", StandardCharsets.UTF_8.name())

  def publish(pr: PersistentRepr, timestamp: Instant): Unit = {
    val pid = pr.persistenceId
    val entityType = PersistenceId.extractEntityType(pid)
    val slice = persistenceExt.sliceForPersistenceId(pid)

    val offset = TimestampOffset(timestamp, timestamp, Map(pid -> pr.sequenceNr))
    val payload =
      pr.payload match {
        case Tagged(payload, _) =>
          // eventsByTag not implemented (see issue #82), but events can still be tagged, so we unwrap this tagged event.
          payload

        case other => other
      }

    val envelope = new EventEnvelope(
      offset,
      pid,
      pr.sequenceNr,
      Option(payload),
      timestamp.toEpochMilli,
      pr.metadata,
      entityType,
      slice)
    eventTopic(entityType, slice) ! Topic.Publish(envelope)
  }
}
