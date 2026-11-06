/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.persistence.r2dbc.internal.oracle

import java.util.Locale

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.internal.ConnectionAdapter
import io.r2dbc.spi.ConnectionFactoryOptions

/**
 * INTERNAL API
 *
 * Adapts the Oracle R2DBC driver to the types used by the plugin. `java.time.Instant` is not supported by the driver,
 * so it is bound and read as a UTC `LocalDateTime`. The `TIMESTAMP` columns of the Oracle DDL have no time zone, so the
 * session time zone doesn't affect them.
 */
@InternalApi
private[r2dbc] object OracleConnections extends ConnectionAdapter.InstantAsUtcLocalDateTime {

  def isOracle(options: ConnectionFactoryOptions): Boolean = {
    def is(option: io.r2dbc.spi.Option[?]): Boolean =
      Option(options.getValue(option)).exists(_.toString.toLowerCase(Locale.ROOT).startsWith("oracle"))
    is(ConnectionFactoryOptions.DRIVER) || is(ConnectionFactoryOptions.PROTOCOL)
  }
}
