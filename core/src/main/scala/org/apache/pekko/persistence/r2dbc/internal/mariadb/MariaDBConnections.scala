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

package org.apache.pekko.persistence.r2dbc.internal.mariadb

import java.util.Locale

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.internal.ConnectionAdapter
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Option

/**
 * INTERNAL API
 *
 * Adapts the MariaDB R2DBC driver to the types used by the plugin. The driver doesn't support `java.time.Instant`, so
 * it is bound and read as a UTC `LocalDateTime`. That requires the session time zone to be UTC, which is set with the
 * [[TimezoneOption]] of the driver.
 */
@InternalApi
private[r2dbc] object MariaDBConnections extends ConnectionAdapter with ConnectionAdapter.InstantAsUtcLocalDateTime {

  val TimezoneOption: Option[String] = Option.valueOf("timezone")

  def isMariaDB(options: ConnectionFactoryOptions): Boolean = {
    // the protocol is the driver when it is used through r2dbc-pool, e.g. r2dbc:pool:mariadb://
    def is(option: Option[?]): Boolean =
      scala.Option(options.getValue(option)).exists(_.toString.toLowerCase(Locale.ROOT) == "mariadb")
    is(ConnectionFactoryOptions.DRIVER) || is(ConnectionFactoryOptions.PROTOCOL)
  }
}
