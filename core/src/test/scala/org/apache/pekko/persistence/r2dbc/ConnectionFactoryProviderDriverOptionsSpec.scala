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
import java.util.concurrent.ConcurrentHashMap

import scala.util.Try
import scala.util.Using

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.ConnectionFactoryProvider.ConnectionFactoryOptionsCustomizer
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.asyncer.r2dbc.mysql.MySqlConnectionFactoryProvider
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import org.scalatest.wordspec.AnyWordSpecLike

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

  /**
   * Loads the plugin's classes itself, and hides the PostgreSQL driver's classes from them, as for an application
   * that only has the MySQL driver on the classpath.
   */
  class WithoutPostgresClassLoader(parent: ClassLoader) extends ClassLoader(parent) {
    override def loadClass(name: String, resolve: Boolean): Class[?] =
      getClassLoadingLock(name).synchronized {
        if (name.startsWith("io.r2dbc.postgresql.")) throw new ClassNotFoundException(name)
        else if (name.startsWith("org.apache.pekko.persistence.r2dbc.")) {
          val loaded = findLoadedClass(name)
          if (loaded != null) loaded
          else {
            val stream = getParent.getResourceAsStream(name.replace('.', '/') + ".class")
            if (stream == null) throw new ClassNotFoundException(name)
            val bytes = Using.resource(stream)(_.readAllBytes())
            defineClass(name, bytes, 0, bytes.length)
          }
        } else super.loadClass(name, resolve)
      }
  }

  // no database is needed, the connection factories are created but no connections are made
  val config: Config = ConfigFactory
    .parseString(s"""
    capturing = $${pekko.persistence.r2dbc.connection-factory} {
      initial-size = 0
      connection-factory-options-customizer = "${classOf[CapturingCustomizer].getName}"
    }
    mysql-ssl-options {
      enabled = on
      mode = "verify_identity"
      root-cert = "root.crt"
      cert = "client.crt"
      key = "client.key"
      password = "secret"
    }
    postgres-ssl = $${capturing} {
      driver = "postgres"
      database = "postgres-ssl"
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
    mysql-ssl = $${capturing} {
      driver = "mysql"
      port = 3306
      database = "mysql-ssl"
      ssl = $${mysql-ssl-options}
    }
    # the driver comes from the url, the driver setting stays postgres
    mysql-ssl-url = $${capturing} {
      url = "r2dbc:mysql://root@localhost:3306/mysql-ssl-url"
      ssl = $${mysql-ssl-options}
    }
    # the r2dbc-pool driver, with the database driver as the protocol
    postgres-pool-url = $${capturing} {
      url = "r2dbc:pool:postgresql://postgres@localhost:5432/postgres-pool-url"
      statement-cache-size = 100
    }
    mysql-pool-url = $${capturing} {
      url = "r2dbc:pool:mysql://root@localhost:3306/mysql-pool-url"
      ssl = $${mysql-ssl-options}
    }
    # without the customizer, whose class the isolated provider can't use
    mysql-without-postgres = $${pekko.persistence.r2dbc.connection-factory} {
      initial-size = 0
      driver = "mysql"
      port = 3306
      ssl = $${mysql-ssl-options}
    }
    """)
    .withFallback(ConfigFactory.load())
    .resolve()
}

class ConnectionFactoryProviderDriverOptionsSpec
    extends ScalaTestWithActorTestKit(ConnectionFactoryProviderDriverOptionsSpec.config)
    with AnyWordSpecLike {
  import ConnectionFactoryProviderDriverOptionsSpec._

  private def mySqlOptions(path: String): ConnectionFactoryOptions = {
    ConnectionFactoryProvider(system).connectionFactoryFor(path) should not be null
    options.get(path)
  }

  "ConnectionFactoryProvider" should {
    "set the PostgreSQL driver's options under the names the driver reads" in {
      ConnectionFactoryProvider(system).connectionFactoryFor("postgres-ssl") should not be null
      val postgres = options.get("postgres-ssl")
      postgres.getValue(PostgresqlConnectionFactoryProvider.FORCE_BINARY) shouldBe java.lang.Boolean.TRUE
      postgres.getValue(PostgresqlConnectionFactoryProvider.PREFER_ATTACHED_BUFFERS) shouldBe java.lang.Boolean.TRUE
      val cacheQueries = PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES
      postgres.getValue(cacheQueries) shouldBe Integer.valueOf(100)
      postgres.getValue(PostgresqlConnectionFactoryProvider.STATEMENT_TIMEOUT) shouldBe Duration.ofSeconds(5)
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
      ConnectionFactoryProvider(system).connectionFactoryFor("postgres-pool-url") should not be null
      val postgres = options.get("postgres-pool-url")
      postgres.getValue(PostgresqlConnectionFactoryProvider.FORCE_BINARY) shouldBe java.lang.Boolean.TRUE
      postgres.getValue(PostgresqlConnectionFactoryProvider.PREFER_ATTACHED_BUFFERS) shouldBe java.lang.Boolean.TRUE
      val cacheQueries = PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES
      postgres.getValue(cacheQueries) shouldBe Integer.valueOf(100)
    }

    Seq(
      "mysql-ssl" -> "the driver setting",
      "mysql-ssl-url" -> "the url",
      "mysql-pool-url" -> "the protocol of the pool driver in the url").foreach { case (path, source) =>
      s"pass the SSL options to the MySQL driver, and none of the PostgreSQL driver's, with the driver from $source" in {
        val mysql = mySqlOptions(path)
        mysql.getValue(MySqlConnectionFactoryProvider.SSL_MODE).asInstanceOf[Any] shouldBe "verify_identity"
        mysql.getValue(MySqlConnectionFactoryProvider.SSL_CA) shouldBe "root.crt"
        mysql.getValue(MySqlConnectionFactoryProvider.SSL_CERT) shouldBe "client.crt"
        mysql.getValue(MySqlConnectionFactoryProvider.SSL_KEY) shouldBe "client.key"
        mysql.getValue(MySqlConnectionFactoryProvider.SSL_KEY_PASSWORD).asInstanceOf[Any] shouldBe "secret"
        Seq(
          PostgresqlConnectionFactoryProvider.FORCE_BINARY,
          PostgresqlConnectionFactoryProvider.PREFER_ATTACHED_BUFFERS,
          PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES,
          PostgresqlConnectionFactoryProvider.SSL_ROOT_CERT,
          PostgresqlConnectionFactoryProvider.SSL_PASSWORD).foreach { option =>
          withClue(s"$option:") { mysql.hasOption(option) shouldBe false }
        }
      }
    }

    "create a MySQL connection factory without the PostgreSQL driver on the classpath" in {
      val loader = new WithoutPostgresClassLoader(getClass.getClassLoader)
      val providerClass = loader.loadClass(classOf[ConnectionFactoryProvider].getName)
      providerClass.getClassLoader shouldBe loader
      val provider = providerClass.getConstructor(classOf[ActorSystem[?]]).newInstance(system)
      val factory = providerClass
        .getMethod("connectionFactoryFor", classOf[String])
        .invoke(provider, "mysql-without-postgres")
        .asInstanceOf[ConnectionFactory]
      factory.getMetadata.getName shouldBe "MySQL"
    }
  }
}
