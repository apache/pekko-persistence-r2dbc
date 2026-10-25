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

package org.apache.pekko.persistence.r2dbc.internal.h2

import java.time.Instant

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.DbTimestampMonotonicIncreasing
import pekko.persistence.r2dbc.UseAppTimestamp
import io.r2dbc.spi.Row

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] object H2Utils {

  def tagsFromDb(row: Row, columnName: String): Set[String] = {
    // needs to be picked up with Object even though it is an Array[String]
    // https://github.com/r2dbc/r2dbc-h2/issues/208
    row.get(columnName, classOf[AnyRef]) match {
      case null              => Set.empty[String]
      case entries: Array[?] => entries.iterator.map(_.toString).toSet
      case other             => throw new IllegalStateException(s"Unexpected tags column type [${other.getClass}]")
    }
  }

  /**
   * SQL literal for a `TIMESTAMP WITH TIME ZONE` value.
   */
  def timestampLiteral(instant: Instant): String =
    s"TIMESTAMP WITH TIME ZONE '$instant'"

  /**
   * The database runs in the same process, so the application timestamp is the database timestamp.
   */
  def settingRequirements(settings: UseAppTimestamp with DbTimestampMonotonicIncreasing): Unit = {
    require(settings.useAppTimestamp, "use-app-timestamp config must be on for H2 support")
    require(
      settings.dbTimestampMonotonicIncreasing,
      "db-timestamp-monotonic-increasing config must be on for H2 support")
  }
}
