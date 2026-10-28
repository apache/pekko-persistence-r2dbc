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

import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

import scala.util.Try

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.ConnectionFactorySettings
import pekko.persistence.r2dbc.Dialect
import pekko.persistence.r2dbc.internal.ConnectionAdapter
import com.typesafe.config.Config
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
private[r2dbc] object MariaDBConnections extends ConnectionAdapter.InstantAsUtcLocalDateTime {

  val Driver = "mariadb"

  val TimezoneOption: Option[String] = Option.valueOf("timezone")

  // an offset rather than a zone name, which the server only knows when its time zone tables are loaded
  val UtcTimezone = "+00:00"

  val SslModeOption: Option[String] = Option.valueOf("sslMode")
  val ServerSslCertOption: Option[String] = Option.valueOf("serverSslCert")
  val ClientSslCertOption: Option[String] = Option.valueOf("clientSslCert")
  val ClientSslKeyOption: Option[String] = Option.valueOf("clientSslKey")
  val ClientSslPasswordOption: Option[String] = Option.valueOf("clientSslPassword")

  def isMariaDB(options: ConnectionFactoryOptions): Boolean =
    scala.Option(options.getValue(ConnectionFactoryOptions.DRIVER)).exists(
      _.toString.toLowerCase(Locale.ROOT) == Driver)

  /**
   * Sets the UTC session time zone, unless the connection options already define it. Fails if they define another
   * time zone, as `Instant` would then be stored and read with the wrong offset.
   */
  def requireUtcTimezone(options: ConnectionFactoryOptions): ConnectionFactoryOptions =
    scala.Option(options.getValue(TimezoneOption)).map(_.toString) match {
      case None                              => ConnectionFactoryOptions.builder().from(options).option(TimezoneOption, UtcTimezone).build()
      case Some(timezone) if isUtc(timezone) => options
      case Some(timezone)                    =>
        throw new IllegalArgumentException(
          s"The [timezone] option of the MariaDB driver must be UTC, but is [$timezone]. The plugin sets it to " +
          "UTC, don't override it in the connection url or with a connection-factory-options-customizer.")
    }

  private def isUtc(timezone: String): Boolean =
    Try(ZoneId.of(timezone.trim).normalized() == ZoneOffset.UTC).getOrElse(false)

  /**
   * The `ssl` settings of the connection factory as options of the MariaDB driver, which has other names and values
   * for them than the Postgres driver.
   */
  def sslOptions(settings: ConnectionFactorySettings): Seq[(Option[String], String)] = {
    val sslMode = settings.sslMode.trim.toLowerCase(Locale.ROOT) match {
      case "" | "verify-full" => "VERIFY_FULL"
      case "verify-ca"        => "VERIFY_CA"
      case "require"          => "TRUST"
      case other              =>
        throw new IllegalArgumentException(
          s"ssl.mode [$other] is not supported with the MariaDB driver, use require, verify-ca or verify-full.")
    }
    Seq(
      SslModeOption -> sslMode,
      ServerSslCertOption -> settings.sslRootCert,
      ClientSslCertOption -> settings.sslCert,
      ClientSslKeyOption -> settings.sslKey,
      ClientSslPasswordOption -> settings.sslPassword).filter { case (_, value) => value.nonEmpty }
  }

  /**
   * The MariaDB driver and the `mariadb` dialect are only supported together: the driver needs the snapshot upsert of
   * the dialect, and the dialect needs the UTC session and `Instant` conversion that are set up for the driver.
   */
  def requireMatchingDialect(dialect: Dialect, connectionFactoryConfig: Config): Unit = {
    val url =
      if (connectionFactoryConfig.hasPath("url")) connectionFactoryConfig.getString("url").trim
      else ""
    val mariaDBDriver =
      if (url.nonEmpty) isMariaDB(ConnectionFactoryOptions.parse(url))
      else connectionFactoryConfig.getString("driver").toLowerCase(Locale.ROOT) == Driver
    if (mariaDBDriver && dialect != Dialect.MariaDB)
      throw new IllegalArgumentException(
        s"The MariaDB driver requires dialect = mariadb, but the dialect is [${dialectName(dialect)}].")
    if (!mariaDBDriver && dialect == Dialect.MariaDB)
      throw new IllegalArgumentException("The mariadb dialect requires the MariaDB driver, driver = mariadb.")
  }

  private def dialectName(dialect: Dialect): String = dialect.toString.toLowerCase(Locale.ROOT)
}
