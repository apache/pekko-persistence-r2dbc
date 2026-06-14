/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2022 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.internal

import org.apache.pekko
import pekko.annotation.InternalStableApi
import pekko.persistence.query.typed.EventEnvelope

/**
 * INTERNAL API
 */
@InternalStableApi private[pekko] object EnvelopeOrigin {
  val SourceQuery = ""
  val SourceBacktracking = "BT"
  val SourcePubSub = "PS"

  def fromQuery(env: EventEnvelope[?]): Boolean =
    env.source == SourceQuery

  def fromBacktracking(env: EventEnvelope[?]): Boolean =
    env.source == SourceBacktracking

  def fromPubSub(env: EventEnvelope[?]): Boolean =
    env.source == SourcePubSub

  def isFilteredEvent(env: Any): Boolean =
    env match {
      case e: EventEnvelope[?] => e.filtered
      case _                   => false
    }
}
