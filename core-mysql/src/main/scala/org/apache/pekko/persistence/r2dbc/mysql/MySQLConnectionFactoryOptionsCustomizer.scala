/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
