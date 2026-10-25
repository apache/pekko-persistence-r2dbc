/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.internal

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.BufferSize
import pekko.persistence.r2dbc.BySliceQuerySettings
import pekko.persistence.r2dbc.DbTimestampMonotonicIncreasing
import pekko.persistence.r2dbc.RefreshInterval
import pekko.persistence.r2dbc.UseAppTimestamp

/**
 * INTERNAL API: Compound settings types. Scala 3.10+ no longer accepts `with` as a type operator and Scala 2.13 does
 * not have `&`, so these are defined per Scala version.
 */
@InternalApi private[r2dbc] object SettingsTypes {
  type BySliceQueryAllSettings = BySliceQuerySettings & RefreshInterval & BufferSize
  type MySQLRequiredSettings = UseAppTimestamp & DbTimestampMonotonicIncreasing
}
