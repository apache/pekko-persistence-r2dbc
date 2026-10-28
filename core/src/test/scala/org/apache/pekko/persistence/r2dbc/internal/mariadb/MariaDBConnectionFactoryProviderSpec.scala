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

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.ConnectionFactoryProvider
import pekko.persistence.r2dbc.ConnectionFactoryProvider.ConnectionFactoryOptionsCustomizer
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import org.scalatest.wordspec.AnyWordSpecLike

object MariaDBConnectionFactoryProviderSpec {

  class AutoTimezoneCustomizer(system: ActorSystem[?]) extends ConnectionFactoryOptionsCustomizer {
    override def apply(builder: ConnectionFactoryOptions.Builder, config: Config): ConnectionFactoryOptions.Builder =
      builder.option(MariaDBConnections.TimezoneOption, "auto")
  }

  // no database is needed, the connection factories are created but no connections are made
  val config: Config = ConfigFactory
    .parseString(s"""
    mariadb-base = $${pekko.persistence.r2dbc.connection-factory} {
      driver = "mariadb"
      host = "localhost"
      port = 3306
      initial-size = 0
    }
    mariadb-default = $${mariadb-base}
    mariadb-customizer = $${mariadb-base} {
      connection-factory-options-customizer = "${classOf[AutoTimezoneCustomizer].getName}"
    }
    mariadb-url = $${mariadb-base} {
      url = "r2dbc:mariadb://localhost:3306/db?timezone=SYSTEM"
    }
    """)
    .withFallback(ConfigFactory.load())
    .resolve()
}

class MariaDBConnectionFactoryProviderSpec
    extends ScalaTestWithActorTestKit(MariaDBConnectionFactoryProviderSpec.config)
    with AnyWordSpecLike {

  "ConnectionFactoryProvider with the MariaDB driver" should {
    "create the connection factory with the UTC session time zone" in {
      ConnectionFactoryProvider(system).connectionFactoryFor("mariadb-default") should not be null
    }

    "fail if a connection-factory-options-customizer sets another session time zone" in {
      intercept[IllegalArgumentException] {
        ConnectionFactoryProvider(system).connectionFactoryFor("mariadb-customizer")
      }.getMessage should include("must be UTC, but is [auto]")
    }

    "fail if the url sets another session time zone" in {
      intercept[IllegalArgumentException] {
        ConnectionFactoryProvider(system).connectionFactoryFor("mariadb-url")
      }.getMessage should include("must be UTC, but is [SYSTEM]")
    }
  }
}
