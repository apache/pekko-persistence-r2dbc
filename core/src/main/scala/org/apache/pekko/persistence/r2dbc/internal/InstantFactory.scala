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

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[pekko] object InstantFactory {

  /**
   * Current time truncated to microseconds. The reason for using microseconds is that Postgres timestamps has the
   * resolution of microseconds but some OS/JDK (Linux/JDK17) has Instant resolution of nanoseconds.
   */
  def now(): Instant =
    Instant.now().truncatedTo(ChronoUnit.MICROS)

  private val lastMicros = new AtomicLong(0L)

  /**
   * Like [[now]], but later than any instant previously returned by this method in this JVM, by at least one
   * microsecond. The wall clock can return the same value for a while, about 0.5 ms on Windows.
   */
  def nowStrictlyIncreasing(): Instant = {
    val nowMicros = ChronoUnit.MICROS.between(Instant.EPOCH, now())
    val next = lastMicros.updateAndGet(prev => math.max(prev + 1, nowMicros))
    Instant.EPOCH.plus(next, ChronoUnit.MICROS)
  }

}
