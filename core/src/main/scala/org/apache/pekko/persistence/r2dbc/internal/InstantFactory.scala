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

}
