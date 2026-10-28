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

import java.time.Instant
import java.time.LocalDateTime

import scala.collection.mutable

import org.apache.pekko
import pekko.persistence.r2dbc.ConnectionFactorySettings
import pekko.persistence.r2dbc.Dialect
import pekko.persistence.r2dbc.internal.ConnectionAdapter
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Result
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import io.r2dbc.spi.Statement
import org.reactivestreams.Publisher
import org.scalatest.TestSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MariaDBConnectionsSpec extends AnyWordSpec with TestSuite with Matchers {

  private val instant = Instant.parse("2026-10-27T11:19:11.123456Z")
  private val utcLocalDateTime = LocalDateTime.parse("2026-10-27T11:19:11.123456")

  private class RecordingStatement extends Statement {
    val bound = mutable.Map.empty[Any, Any]
    val boundNull = mutable.Map.empty[Any, Class[?]]
    override def add(): Statement = this
    override def bind(index: Int, value: Any): Statement = { bound(index) = value; this }
    override def bind(name: String, value: Any): Statement = { bound(name) = value; this }
    override def bindNull(index: Int, tpe: Class[?]): Statement = { boundNull(index) = tpe; this }
    override def bindNull(name: String, tpe: Class[?]): Statement = { boundNull(name) = tpe; this }
    override def execute(): Publisher[? <: Result] = throw new UnsupportedOperationException
  }

  // a row like the MariaDB driver returns, which only supports LocalDateTime for timestamps
  private class LocalDateTimeRow(value: LocalDateTime) extends Row {
    override def getMetadata: RowMetadata = throw new UnsupportedOperationException
    override def get[T](index: Int, tpe: Class[T]): T = get("col", tpe)
    override def get[T](name: String, tpe: Class[T]): T =
      if (tpe == classOf[LocalDateTime]) value.asInstanceOf[T]
      else if (tpe == classOf[String]) "text".asInstanceOf[T]
      else throw new IllegalArgumentException(s"No decoder for type ${tpe.getName}")
  }

  "MariaDBConnections" should {
    "bind Instant as UTC LocalDateTime" in {
      val recording = new RecordingStatement
      new ConnectionAdapter.AdaptedStatement(recording, MariaDBConnections)
        .bind(0, instant)
        .bind("ts", instant)
        .bind(1, "text")
      recording.bound(0) shouldBe utcLocalDateTime
      recording.bound("ts") shouldBe utcLocalDateTime
      recording.bound(1) shouldBe "text"
    }

    "bind null Instant as LocalDateTime" in {
      val recording = new RecordingStatement
      new ConnectionAdapter.AdaptedStatement(recording, MariaDBConnections)
        .bindNull(0, classOf[Instant])
        .bindNull("ts", classOf[Instant])
        .bindNull(1, classOf[String])
      recording.boundNull(0) shouldBe classOf[LocalDateTime]
      recording.boundNull("ts") shouldBe classOf[LocalDateTime]
      recording.boundNull(1) shouldBe classOf[String]
    }

    "read UTC LocalDateTime as Instant" in {
      val row = new ConnectionAdapter.AdaptedRow(new LocalDateTimeRow(utcLocalDateTime), MariaDBConnections)
      row.get("ts", classOf[Instant]) shouldBe instant
      row.get(0, classOf[Instant]) shouldBe instant
      row.get("col", classOf[String]) shouldBe "text"
    }

    "read null timestamp as null Instant" in {
      val row = new ConnectionAdapter.AdaptedRow(new LocalDateTimeRow(null), MariaDBConnections)
      row.get("ts", classOf[Instant]) shouldBe null
    }

    "detect the MariaDB driver" in {
      def driver(name: String) =
        MariaDBConnections.isMariaDB(
          ConnectionFactoryOptions.builder().option(ConnectionFactoryOptions.DRIVER, name).build())
      def url(url: String) = MariaDBConnections.isMariaDB(ConnectionFactoryOptions.parse(url))
      driver("mariadb") shouldBe true
      driver("mysql") shouldBe false
      driver("postgresql") shouldBe false
      url("r2dbc:mariadb://localhost:3306/db") shouldBe true
      url("r2dbc:mysql://localhost:3306/db") shouldBe false
      // the plugin has its own connection pool, a pool url would nest r2dbc-pool in it
      url("r2dbc:pool:mariadb://localhost:3306/db") shouldBe false
    }

    "set the UTC session time zone" in {
      val options = ConnectionFactoryOptions.builder().option(ConnectionFactoryOptions.DRIVER, "mariadb").build()
      MariaDBConnections.requireUtcTimezone(options).getValue(MariaDBConnections.TimezoneOption) shouldBe "+00:00"
    }

    "accept a UTC session time zone" in {
      for (timezone <- Seq("+00:00", "UTC", "Z", "Etc/UTC")) {
        val options = withTimezone(timezone)
        MariaDBConnections.requireUtcTimezone(options) shouldBe options
      }
    }

    "fail for another session time zone" in {
      for (timezone <- Seq("auto", "disable", "SYSTEM", "Europe/Vilnius", "+02:00")) {
        intercept[IllegalArgumentException] {
          MariaDBConnections.requireUtcTimezone(withTimezone(timezone))
        }.getMessage should include(s"must be UTC, but is [$timezone]")
      }
    }

    "map the ssl settings to the options of the MariaDB driver" in {
      sslOptions("""
        ssl.enabled = on
        ssl.mode = "verify-ca"
        ssl.root-cert = "/certs/ca.pem"
        ssl.cert = "/certs/client-cert.pem"
        ssl.key = "/certs/client-key.pem"
        ssl.password = "secret"
        """) shouldBe Map(
        "sslMode" -> "VERIFY_CA",
        "serverSslCert" -> "/certs/ca.pem",
        "clientSslCert" -> "/certs/client-cert.pem",
        "clientSslKey" -> "/certs/client-key.pem",
        "clientSslPassword" -> "secret")
    }

    "map the ssl modes to the modes of the MariaDB driver" in {
      sslOptions("ssl.mode = \"\"") shouldBe Map("sslMode" -> "VERIFY_FULL")
      sslOptions("ssl.mode = verify-full") shouldBe Map("sslMode" -> "VERIFY_FULL")
      sslOptions("ssl.mode = VERIFY-CA") shouldBe Map("sslMode" -> "VERIFY_CA")
      sslOptions("ssl.mode = require") shouldBe Map("sslMode" -> "TRUST")
    }

    "fail for ssl modes without an equivalent in the MariaDB driver" in {
      for (mode <- Seq("allow", "prefer", "tunnel", "disable")) {
        intercept[IllegalArgumentException] {
          sslOptions(s"ssl.mode = $mode")
        }.getMessage should include(s"ssl.mode [$mode] is not supported with the MariaDB driver")
      }
    }

    "require the mariadb dialect for the MariaDB driver and the other way around" in {
      MariaDBConnections.requireMatchingDialect(Dialect.MariaDB, connectionFactoryConfig("driver = mariadb"))
      MariaDBConnections.requireMatchingDialect(Dialect.MySQL, connectionFactoryConfig("driver = mysql"))
      MariaDBConnections.requireMatchingDialect(Dialect.Postgres, connectionFactoryConfig("driver = postgres"))
      MariaDBConnections.requireMatchingDialect(
        Dialect.MariaDB,
        connectionFactoryConfig("url = \"r2dbc:mariadb://localhost:3306/db\""))

      intercept[IllegalArgumentException] {
        MariaDBConnections.requireMatchingDialect(Dialect.MySQL, connectionFactoryConfig("driver = mariadb"))
      }.getMessage should include("The MariaDB driver requires dialect = mariadb, but the dialect is [mysql]")
      intercept[IllegalArgumentException] {
        MariaDBConnections.requireMatchingDialect(
          Dialect.MySQL,
          connectionFactoryConfig("url = \"r2dbc:mariadb://localhost:3306/db\""))
      }.getMessage should include("The MariaDB driver requires dialect = mariadb")
      intercept[IllegalArgumentException] {
        MariaDBConnections.requireMatchingDialect(Dialect.MariaDB, connectionFactoryConfig("driver = mysql"))
      }.getMessage should include("The mariadb dialect requires the MariaDB driver")
      intercept[IllegalArgumentException] {
        MariaDBConnections.requireMatchingDialect(
          Dialect.MariaDB,
          connectionFactoryConfig("url = \"r2dbc:pool:mariadb://localhost:3306/db\""))
      }.getMessage should include("The mariadb dialect requires the MariaDB driver")
    }
  }

  private def withTimezone(timezone: String): ConnectionFactoryOptions =
    ConnectionFactoryOptions
      .builder()
      .option(ConnectionFactoryOptions.DRIVER, "mariadb")
      .option(MariaDBConnections.TimezoneOption, timezone)
      .build()

  private def connectionFactoryConfig(settings: String): Config =
    ConfigFactory
      .parseString(settings)
      .withFallback(ConfigFactory.defaultReference().getConfig("pekko.persistence.r2dbc.connection-factory"))

  private def sslOptions(settings: String): Map[String, String] =
    MariaDBConnections
      .sslOptions(ConnectionFactorySettings(connectionFactoryConfig(settings)))
      .map { case (option, value) => option.name() -> value }
      .toMap
}
