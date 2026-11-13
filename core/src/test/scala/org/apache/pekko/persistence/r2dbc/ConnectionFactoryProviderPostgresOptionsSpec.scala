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

import java.time.Duration

import scala.util.Try

import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.postgresql.client.SSLMode
import io.r2dbc.spi.ConnectionFactoryOptions

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

import org.scalatest.Outcome
import org.scalatest.Pending
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Checks the PostgreSQL driver's options against the driver's own constants. The PostgreSQL driver is only on the test
 * classpath for the dialects that use it, so the tests are pending for the other dialects.
 */
class ConnectionFactoryProviderPostgresOptionsSpec
    extends ScalaTestWithActorTestKit(ConnectionFactoryProviderDriverOptionsSpec.config)
    with AnyWordSpecLike {
  import ConnectionFactoryProviderDriverOptionsSpec.options

  override def withFixture(test: NoArgTest): Outcome =
    TestConfig.dialect match {
      case Dialect.Postgres | Dialect.Yugabyte => super.withFixture(test)
      case _                                   => Pending
    }

  private def optionsOf(path: String): ConnectionFactoryOptions = {
    ConnectionFactoryProvider(system).connectionFactoryFor(path) should not be null
    options.get(path)
  }

  private def shouldHavePostgresOptions(postgres: ConnectionFactoryOptions): Unit = {
    postgres.getValue(PostgresqlConnectionFactoryProvider.FORCE_BINARY) shouldBe java.lang.Boolean.TRUE
    postgres.getValue(PostgresqlConnectionFactoryProvider.PREFER_ATTACHED_BUFFERS) shouldBe java.lang.Boolean.TRUE
    postgres.getValue(PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES) shouldBe
    Integer.valueOf(100)
  }

  "ConnectionFactoryProvider" should {
    "set the PostgreSQL driver's options under the names the driver reads" in {
      val postgres = optionsOf("postgres-options")
      shouldHavePostgresOptions(postgres)
      postgres.getValue(PostgresqlConnectionFactoryProvider.STATEMENT_TIMEOUT) shouldBe Duration.ofSeconds(5)
      // the driver converts the name with SSLMode.fromValue
      postgres.getValue(PostgresqlConnectionFactoryProvider.SSL_MODE).asInstanceOf[Any] shouldBe "verify-full"
      postgres.getValue(PostgresqlConnectionFactoryProvider.SSL_PASSWORD) shouldBe "secret"
    }

    "set the PostgreSQL driver's certificate and key options under the names the driver reads" in {
      // the driver then fails to read the certificates, which don't exist
      Try(ConnectionFactoryProvider(system).connectionFactoryFor("postgres-ssl-files"))
      val postgres = options.get("postgres-ssl-files")
      postgres.getValue(PostgresqlConnectionFactoryProvider.SSL_ROOT_CERT) shouldBe "root.crt"
      postgres.getValue(PostgresqlConnectionFactoryProvider.SSL_CERT) shouldBe "client.crt"
      postgres.getValue(PostgresqlConnectionFactoryProvider.SSL_KEY) shouldBe "client.key"
    }

    "set the PostgreSQL driver's options when the url has it as the protocol of the pool driver" in {
      shouldHavePostgresOptions(optionsOf("postgres-pool-url"))
    }

    Seq("postgres-proxy-url" -> "r2dbc-proxy", "postgres-pool-proxy-url" -> "r2dbc-pool and r2dbc-proxy").foreach {
      case (path, wrappers) =>
        s"set the PostgreSQL driver's options when it is wrapped by $wrappers" in {
          // r2dbc-proxy isn't on the classpath, so creating the connection factory fails after the customizer
          Try(ConnectionFactoryProvider(system).connectionFactoryFor(path))
          shouldHavePostgresOptions(options.get(path))
        }
    }

    "pass ssl-mode values that the PostgreSQL driver converts" in {
      SSLMode.fromValue("VERIFY_FULL") shouldBe SSLMode.VERIFY_FULL
      SSLMode.fromValue("verify-full") shouldBe SSLMode.VERIFY_FULL
    }
  }
}
