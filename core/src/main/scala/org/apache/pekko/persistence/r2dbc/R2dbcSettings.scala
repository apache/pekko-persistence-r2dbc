/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc

import java.util.Locale

import scala.concurrent.duration._

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.annotation.InternalStableApi
import pekko.util.JavaDurationConverters._
import com.typesafe.config.Config
import pekko.util.Helpers.toRootLowerCase

/**
 * INTERNAL API
 */
@InternalStableApi
sealed trait Dialect

/**
 * INTERNAL API
 */
@InternalStableApi
object Dialect {
  case object Postgres extends Dialect
  case object Yugabyte extends Dialect

  /** @since 1.1.0 */
  case object MySQL extends Dialect

  /** @since 1.1.0 */
  def fromString(value: String): Dialect = {
    toRootLowerCase(value) match {
      case "yugabyte" => Dialect.Yugabyte
      case "postgres" => Dialect.Postgres
      case "mysql"    => Dialect.MySQL
      case other =>
        throw new IllegalArgumentException(
          s"Unknown dialect [$other]. Supported dialects are [yugabyte, postgres, mysql].")
    }
  }
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class JournalSettings(config: Config) {

  val shared: SharedSettings = SharedSettings(config.getConfig("shared"))

  val journalTable: String = config.getString("table")
  val journalTableWithSchema: String = shared.schema.map(_ + ".").getOrElse("") + journalTable
}

/**
 * INTERNAL API
 */
@InternalStableApi
object JournalSettings {
  def apply(config: Config): JournalSettings =
    new JournalSettings(config)
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class SnapshotSettings(config: Config) {

  val shared: SharedSettings = SharedSettings(config.getConfig("shared"))

  val snapshotsTable: String = config.getString("table")
  val snapshotsTableWithSchema: String = shared.schema.map(_ + ".").getOrElse("") + snapshotsTable
}

/**
 * INTERNAL API
 */
@InternalStableApi
object SnapshotSettings {
  def apply(config: Config): SnapshotSettings =
    new SnapshotSettings(config)
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class StateSettings(config: Config) {

  val shared: SharedSettings = SharedSettings(config.getConfig("shared"))

  val durableStateTable: String = config.getString("table")
  val durableStateTableWithSchema: String = shared.schema.map(_ + ".").getOrElse("") + durableStateTable

  val durableStateAssertSingleWriter: Boolean = config.getBoolean("assert-single-writer")
}

/**
 * INTERNAL API
 */
@InternalStableApi
object StateSettings {
  def apply(config: Config): StateSettings =
    new StateSettings(config)
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class QuerySettings(config: Config) {

  val shared: SharedSettings = SharedSettings(config.getConfig("shared"))

  val journalTable: String = config.getString("table")
  val journalTableWithSchema: String = shared.schema.map(_ + ".").getOrElse("") + journalTable

  val deduplicateCapacity: Int = config.getInt("deduplicate-capacity")
}

/**
 * INTERNAL API
 */
@InternalStableApi
object QuerySettings {
  def apply(config: Config): QuerySettings =
    new QuerySettings(config)
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class SharedSettings(config: Config) {

  val journalPublishEvents: Boolean = config.getBoolean("publish-events")

  val dialect: Dialect = Dialect.fromString(config.getString("dialect"))

  val schema: Option[String] = Option(config.getString("schema")).filterNot(_.trim.isEmpty)

  val connectionFactorySettings = ConnectionFactorySettings(config.getConfig("connection-factory"))

  val dbTimestampMonotonicIncreasing: Boolean = config.getBoolean("db-timestamp-monotonic-increasing")

  /**
   * INTERNAL API FIXME remove when https://github.com/yugabyte/yugabyte-db/issues/10995 has been resolved
   */
  @InternalApi private[pekko] val useAppTimestamp: Boolean = config.getBoolean("use-app-timestamp")

  val logDbCallsExceeding: FiniteDuration =
    config.getString("log-db-calls-exceeding").toLowerCase(Locale.ROOT) match {
      case "off" => -1.millis
      case _     => config.getDuration("log-db-calls-exceeding").asScala
    }

  val bufferSize: Int = config.getInt("buffer-size")

  val refreshInterval: FiniteDuration = config.getDuration("refresh-interval").asScala
  val behindCurrentTime: FiniteDuration = config.getDuration("behind-current-time").asScala
  val backtrackingEnabled: Boolean = config.getBoolean("backtracking.enabled")
  val backtrackingWindow: FiniteDuration = config.getDuration("backtracking.window").asScala
  val backtrackingBehindCurrentTime: FiniteDuration = config.getDuration("backtracking.behind-current-time").asScala
  val persistenceIdsBufferSize: Int = config.getInt("persistence-ids.buffer-size")
}

/**
 * INTERNAL API
 */
@InternalStableApi
object SharedSettings {
  def apply(config: Config): SharedSettings =
    new SharedSettings(config)
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class ConnectionFactorySettings(config: Config) {

  val urlOption: Option[String] =
    Option(config.getString("url"))
      .filter(_.trim.nonEmpty)

  val driver: String = config.getString("driver")
  val host: String = config.getString("host")
  val port: Int = config.getInt("port")
  val user: String = config.getString("user")
  val password: String = config.getString("password")
  val database: String = config.getString("database")

  val sslEnabled: Boolean = config.getBoolean("ssl.enabled")
  val sslMode: String = config.getString("ssl.mode")
  val sslRootCert: String = config.getString("ssl.root-cert")

  val initialSize: Int = config.getInt("initial-size")
  val maxSize: Int = config.getInt("max-size")
  val maxIdleTime: FiniteDuration = config.getDuration("max-idle-time").asScala
  val maxLifeTime: FiniteDuration = config.getDuration("max-life-time").asScala

  val connectTimeout: FiniteDuration = config.getDuration("connect-timeout").asScala
  val acquireTimeout: FiniteDuration = config.getDuration("acquire-timeout").asScala
  val acquireRetry: Int = config.getInt("acquire-retry")

  val validationQuery: String = config.getString("validation-query")

  val statementCacheSize: Int = config.getInt("statement-cache-size")

  val connectionFactoryOptionsCustomizer: Option[String] =
    Option(config.getString("connection-factory-options-customizer")).filter(_.trim.nonEmpty)
}

/**
 * INTERNAL API
 */
@InternalStableApi
object ConnectionFactorySettings {
  def apply(config: Config): ConnectionFactorySettings =
    new ConnectionFactorySettings(config)
}
