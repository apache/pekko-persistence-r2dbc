/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.mysql

import com.typesafe.config.Config
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Option
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.ConnectionFactoryProvider.ConnectionFactoryOptionsCustomizer

class MySQLConnectionFactoryOptionsCustomizer(system: ActorSystem[_]) extends ConnectionFactoryOptionsCustomizer {
  override def apply(builder: ConnectionFactoryOptions.Builder, config: Config): ConnectionFactoryOptions.Builder = {
    // Either `connectionTimeZone = SERVER` or `forceConnectionTimeZoneToSession = true` need to be set for timezones to work correctly,
    // likely caused by bug in https://github.com/asyncer-io/r2dbc-mysql/pull/240.
    builder.option(Option.valueOf("connectionTimeZone"), "SERVER")
  }
}
