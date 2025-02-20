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
import pekko.util.Helpers.toRootLowerCase
import pekko.util.JavaDurationConverters._
import com.typesafe.config.Config

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
final class JournalSettings(val config: Config) extends ConnectionSettings with UseConnnectionFactory with BufferSize
    with JournalPublishEvents with DbTimestampMonotonicIncreasing with UseAppTimestamp {

  val journalTable: String = config.getString("table")
  val journalTableWithSchema: String = schema.map(_ + ".").getOrElse("") + journalTable
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
final class SnapshotSettings(val config: Config) extends ConnectionSettings with UseConnnectionFactory {

  val snapshotsTable: String = config.getString("table")
  val snapshotsTableWithSchema: String = schema.map(_ + ".").getOrElse("") + snapshotsTable
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
final class StateSettings(val config: Config) extends ConnectionSettings with UseConnnectionFactory with BufferSize
    with RefreshInterval with BySliceQuerySettings with DbTimestampMonotonicIncreasing with PersistenceIdsQuerySettings
    with UseAppTimestamp {

  val durableStateTable: String = config.getString("table")
  val durableStateTableWithSchema: String = schema.map(_ + ".").getOrElse("") + durableStateTable

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
final class QuerySettings(val config: Config) extends ConnectionSettings with UseConnnectionFactory with BufferSize
    with RefreshInterval with BySliceQuerySettings with JournalPublishEvents with PersistenceIdsQuerySettings {

  val journalTable: String = config.getString("table")
  val journalTableWithSchema: String = schema.map(_ + ".").getOrElse("") + journalTable

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
trait ConnectionSettings {
  def config: Config

  val dialect: Dialect = Dialect.fromString(config.getString("dialect"))

  val schema: Option[String] = Option(config.getString("schema")).filterNot(_.trim.isEmpty)

  val logDbCallsExceeding: FiniteDuration =
    config.getString("log-db-calls-exceeding").toLowerCase(Locale.ROOT) match {
      case "off" => -1.millis
      case _     => config.getDuration("log-db-calls-exceeding").asScala
    }
}

/**
 * INTERNAL API
 */
@InternalStableApi
trait JournalPublishEvents {
  def config: Config

  val journalPublishEvents: Boolean = config.getBoolean("publish-events")
}

/**
 * INTERNAL API
 */
@InternalStableApi
trait DbTimestampMonotonicIncreasing {
  def config: Config

  val dbTimestampMonotonicIncreasing: Boolean = config.getBoolean("db-timestamp-monotonic-increasing")
}

/**
 * INTERNAL API
 */
@InternalStableApi
trait UseAppTimestamp {
  def config: Config

  /**
   * INTERNAL API FIXME remove when https://github.com/yugabyte/yugabyte-db/issues/10995 has been resolved
   */
  @InternalApi private[pekko] val useAppTimestamp: Boolean = config.getBoolean("use-app-timestamp")
}

/**
 * INTERNAL API
 */
@InternalStableApi
trait BufferSize {
  def config: Config

  val bufferSize: Int = config.getInt("buffer-size")
}

/**
 * INTERNAL API
 */
@InternalStableApi
trait RefreshInterval {
  def config: Config

  val refreshInterval: FiniteDuration = config.getDuration("refresh-interval").asScala
}

/**
 * INTERNAL API
 */
@InternalStableApi
trait BySliceQuerySettings {
  def config: Config

  val behindCurrentTime: FiniteDuration = config.getDuration("behind-current-time").asScala
  val backtrackingEnabled: Boolean = config.getBoolean("backtracking.enabled")
  val backtrackingWindow: FiniteDuration = config.getDuration("backtracking.window").asScala
  val backtrackingBehindCurrentTime: FiniteDuration = config.getDuration("backtracking.behind-current-time").asScala
}

/**
 * INTERNAL API
 */
@InternalStableApi
trait PersistenceIdsQuerySettings {
  def config: Config

  val persistenceIdsBufferSize: Int = config.getInt("persistence-ids.buffer-size")
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

/**
 * INTERNAL API
 */
@InternalStableApi
trait UseConnnectionFactory {
  def config: Config

  val useConnectionFactory: String = config.getString("use-connection-factory")
}
