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
import java.util.concurrent.atomic.AtomicLong

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
import pekko.persistence.r2dbc.PublishEventsDynamicSettings
import pekko.persistence.typed.PersistenceId
import org.slf4j.LoggerFactory

/**
 * INTERNAL API
 */
@InternalApi private[pekko] object PubSub extends ExtensionId[PubSub] {
  private val log = LoggerFactory.getLogger(classOf[PubSub])

  def createExtension(system: ActorSystem[_]): PubSub = new PubSub(system)

  // Java API
  def get(system: ActorSystem[_]): PubSub = apply(system)

}

/**
 * INTERNAL API
 */
@InternalApi private[pekko] class PubSub(system: ActorSystem[_]) extends Extension {
  import PubSub.log

  private val topics = new ConcurrentHashMap[String, ActorRef[Any]]
  private val persistenceExt = Persistence(system)

  private val settings = new PublishEventsDynamicSettings(
    system.settings.config.getConfig("pekko.persistence.r2dbc.journal.publish-events-dynamic"))
  private val throughputCollectIntervalMillis = settings.throughputCollectInterval.toMillis
  private val throughputThreshold = settings.throughputThreshold.toDouble
  private val throughputSampler =
    math.min(1000, math.max(1, settings.throughputThreshold / 10)) // 1/10 of threshold, but between 1-1000
  private val throughputCounter = new AtomicLong
  @volatile private var throughput =
    EWMA(0.0, EWMA.alpha(settings.throughputCollectInterval * 2, settings.throughputCollectInterval))

  def eventTopic[Event](entityType: String, slice: Int): ActorRef[Topic.Command[EventEnvelope[Event]]] = {
    val name = topicName(entityType, slice)
    topics
      .computeIfAbsent(name, _ => system.systemActorOf(Topic[EventEnvelope[Event]](name), name).unsafeUpcast[Any])
      .narrow[Topic.Command[EventEnvelope[Event]]]
  }

  private def topicName(entityType: String, slice: Int): String =
    URLEncoder.encode(s"r2dbc-$entityType-$slice", StandardCharsets.UTF_8.name())

  def publish(pr: PersistentRepr, timestamp: Instant): Unit = {

    val n = throughputCounter.incrementAndGet()
    if (n % throughputSampler == 0) {
      val ewma = throughput
      val durationMillis = (System.nanoTime() - ewma.nanoTime) / 1000 / 1000
      if (durationMillis >= throughputCollectIntervalMillis) {
        // doesn't have to be exact so "missed" or duplicate concurrent calls don't matter
        throughputCounter.set(0L)
        val rps = n * 1000.0 / durationMillis
        val newEwma = ewma :+ rps
        throughput = newEwma
        if (ewma.value < throughputThreshold && newEwma.value >= throughputThreshold) {
          log.info("Disabled publishing of events. Throughput greater than [{}] events/s", throughputThreshold)
        } else if (ewma.value >= throughputThreshold && newEwma.value < throughputThreshold) {
          log.info("Enabled publishing of events. Throughput less than [{}] events/s", throughputThreshold)
        } else {
          log.debug(
            "Publishing of events is {}. Throughput is [{}] events/s",
            if (newEwma.value < throughputThreshold) "enabled" else "disabled",
            newEwma.value)
        }
      }
    }

    if (throughput.value < throughputThreshold) {
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
        slice,
        filtered = false,
        source = EnvelopeOrigin.SourcePubSub)
      eventTopic(entityType, slice) ! Topic.Publish(envelope)
    }
  }
}
