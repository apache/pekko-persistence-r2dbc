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

import java.util.concurrent.ConcurrentHashMap

import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.{ Option => R2dbcOption }

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.ConnectionFactoryProvider.ConnectionFactoryOptionsCustomizer

import org.scalatest.wordspec.AnyWordSpecLike

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object ConnectionFactoryProviderDriverOptionsSpec {

  // the options the provider passed to the customizer, by database
  val options = new ConcurrentHashMap[String, ConnectionFactoryOptions]

  class CapturingCustomizer(system: ActorSystem[?]) extends ConnectionFactoryOptionsCustomizer {
    override def apply(builder: ConnectionFactoryOptions.Builder, config: Config): ConnectionFactoryOptions.Builder = {
      val built = builder.build()
      options.put(built.getRequiredValue(ConnectionFactoryOptions.DATABASE).toString, built)
      builder
    }
  }

  // no database is needed, the connection factories are created but no connections are made
  val config: Config = ConfigFactory
    .parseString(s"""
    capturing = $${pekko.persistence.r2dbc.connection-factory} {
      initial-size = 0
      connection-factory-options-customizer = "${classOf[CapturingCustomizer].getName}"
    }
    postgres-options = $${capturing} {
      driver = "postgres"
      database = "postgres-options"
      statement-cache-size = 100
      statement-timeout = 5 seconds
      ssl {
        enabled = on
        mode = "verify-full"
        password = "secret"
      }
    }
    postgres-ssl-files = $${capturing} {
      driver = "postgres"
      database = "postgres-ssl-files"
      ssl {
        enabled = on
        mode = "verify-full"
        root-cert = "root.crt"
        cert = "client.crt"
        key = "client.key"
      }
    }
    # the r2dbc-pool driver, with the database driver as the protocol
    postgres-pool-url = $${capturing} {
      url = "r2dbc:pool:postgresql://postgres@localhost:5432/postgres-pool-url"
      statement-cache-size = 100
    }
    # r2dbc-proxy, which wraps the PostgreSQL driver and isn't on the classpath
    postgres-proxy-url = $${capturing} {
      url = "r2dbc:proxy:postgresql://postgres@localhost:5432/postgres-proxy-url"
      statement-cache-size = 100
    }
    postgres-pool-proxy-url = $${capturing} {
      url = "r2dbc:pool:proxy:postgresql://postgres@localhost:5432/postgres-pool-proxy-url"
      statement-cache-size = 100
    }
    mysql-options = $${capturing} {
      driver = "mysql"
      port = 3306
      database = "mysql-options"
    }
    # the driver comes from the url, the driver setting stays postgres
    mysql-options-url = $${capturing} {
      url = "r2dbc:mysql://root@localhost:3306/mysql-options-url"
    }
    mariadb-options = $${capturing} {
      driver = "mariadb"
      port = 3306
      database = "mariadb-options"
    }
    """)
    .withFallback(ConfigFactory.load())
    .resolve()

  // referred to by name, as the PostgreSQL driver is only on the test classpath for the dialects that use it
  val PostgresOnlyOptions: Seq[R2dbcOption[AnyRef]] =
    Seq("forceBinary", "preferAttachedBuffers", "preparedStatementCacheQueries").map(R2dbcOption.valueOf[AnyRef])
}

/**
 * The PostgreSQL driver's options are checked against the driver by [[ConnectionFactoryProviderPostgresOptionsSpec]].
 * That the plugin works without the PostgreSQL driver is shown by the tests of the other dialects, which run without
 * it on the classpath.
 */
class ConnectionFactoryProviderDriverOptionsSpec
    extends ScalaTestWithActorTestKit(ConnectionFactoryProviderDriverOptionsSpec.config)
    with AnyWordSpecLike {
  import ConnectionFactoryProviderDriverOptionsSpec._

  "ConnectionFactoryProvider" should {
    Seq(
      "mysql-options" -> "the MySQL driver from the driver setting",
      "mysql-options-url" -> "the MySQL driver from the url",
      "mariadb-options" -> "the MariaDB driver").foreach { case (path, driver) =>
      s"not set the PostgreSQL driver's options for $driver" in {
        ConnectionFactoryProvider(system).connectionFactoryFor(path) should not be null
        val other = options.get(path)
        PostgresOnlyOptions.foreach { option =>
          withClue(s"$option:") { other.hasOption(option) shouldBe false }
        }
      }
    }
  }
}
