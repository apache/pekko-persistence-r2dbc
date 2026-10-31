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

package org.apache.pekko.persistence.r2dbc

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.testkit.typed.scaladsl.TestProbe
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.eventstream.EventStream
import pekko.persistence.r2dbc.ConnectionFactoryProvider.ConnectionFactoryOptionsCustomizer
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Option
import org.scalatest.wordspec.AnyWordSpecLike

object MySQLConnectionFactoryProviderSpec {

  final case class TimeZoneOptions(connectionTimeZone: String, forceConnectionTimeZoneToSession: String)

  /** Publishes the time zone options that the plugin has set before the customizer. */
  class RecordingCustomizer(system: ActorSystem[?]) extends ConnectionFactoryOptionsCustomizer {
    override def apply(builder: ConnectionFactoryOptions.Builder, config: Config): ConnectionFactoryOptions.Builder = {
      val options = builder.build()
      def value(name: String): String = String.valueOf(options.getValue(Option.valueOf[AnyRef](name)))
      system.eventStream.tell(
        EventStream.Publish(TimeZoneOptions(value("connectionTimeZone"), value("forceConnectionTimeZoneToSession"))))
      builder
    }
  }

  // no database is needed, the connection factories are created but no connections are made
  val config: Config = ConfigFactory
    .parseString(s"""
    mysql-base = $${pekko.persistence.r2dbc.connection-factory} {
      driver = "mysql"
      host = "localhost"
      port = 3306
      initial-size = 0
      connection-factory-options-customizer = "${classOf[RecordingCustomizer].getName}"
    }
    mysql-driver = $${mysql-base}
    mysql-url = $${mysql-base} {
      # the driver setting isn't used with a url
      driver = "postgres"
      url = "r2dbc:mysql://root:root@localhost:3306/mysql"
    }
    mysql-url-time-zone = $${mysql-url} {
      url = "r2dbc:mysql://root:root@localhost:3306/mysql?connectionTimeZone=SERVER&forceConnectionTimeZoneToSession=false"
    }
    """)
    .withFallback(ConfigFactory.load())
    .resolve()
}

class MySQLConnectionFactoryProviderSpec
    extends ScalaTestWithActorTestKit(MySQLConnectionFactoryProviderSpec.config)
    with AnyWordSpecLike {
  import MySQLConnectionFactoryProviderSpec.TimeZoneOptions

  private def timeZoneOptions(configPath: String): TimeZoneOptions = {
    val probe = TestProbe[TimeZoneOptions]()
    system.eventStream.tell(EventStream.Subscribe(probe.ref))
    ConnectionFactoryProvider(system).connectionFactoryFor(configPath)
    val options = probe.receiveMessage()
    system.eventStream.tell(EventStream.Unsubscribe(probe.ref))
    options
  }

  "ConnectionFactoryProvider with the MySQL driver" should {
    "use the UTC session time zone with the driver setting" in {
      timeZoneOptions("mysql-driver") shouldBe TimeZoneOptions("UTC", "true")
    }

    "use the UTC session time zone with a url" in {
      timeZoneOptions("mysql-url") shouldBe TimeZoneOptions("UTC", "true")
    }

    "keep the time zone options of a url" in {
      timeZoneOptions("mysql-url-time-zone") shouldBe TimeZoneOptions("SERVER", "false")
    }
  }
}
