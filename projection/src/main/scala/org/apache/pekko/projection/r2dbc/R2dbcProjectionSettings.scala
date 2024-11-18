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

package org.apache.pekko.projection.r2dbc

import java.time.{ Duration => JDuration }
import java.util.Locale

import scala.concurrent.duration._
import com.typesafe.config.Config
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.Dialect
import pekko.util.JavaDurationConverters._

object R2dbcProjectionSettings {

  val DefaultConfigPath = "pekko.projection.r2dbc"

  def apply(config: Config): R2dbcProjectionSettings = {
    val logDbCallsExceeding: FiniteDuration =
      config.getString("log-db-calls-exceeding").toLowerCase(Locale.ROOT) match {
        case "off" => -1.millis
        case _     => config.getDuration("log-db-calls-exceeding").asScala
      }

    R2dbcProjectionSettings(
      schema = Option(config.getString("offset-store.schema")).filterNot(_.trim.isEmpty),
      offsetTable = config.getString("offset-store.offset-table"),
      timestampOffsetTable = config.getString("offset-store.timestamp-offset-table"),
      managementTable = config.getString("offset-store.management-table"),
      useConnectionFactory = config.getString("use-connection-factory"),
      timeWindow = config.getDuration("offset-store.time-window"),
      keepNumberOfEntries = config.getInt("offset-store.keep-number-of-entries"),
      evictInterval = config.getDuration("offset-store.evict-interval"),
      deleteInterval = config.getDuration("offset-store.delete-interval"),
      logDbCallsExceeding,
      dialect = Dialect.fromString(config.getString("dialect")))
  }

  def apply(system: ActorSystem[_]): R2dbcProjectionSettings =
    apply(system.settings.config.getConfig(DefaultConfigPath))

  def apply(
      schema: Option[String],
      offsetTable: String,
      timestampOffsetTable: String,
      managementTable: String,
      useConnectionFactory: String,
      timeWindow: JDuration,
      keepNumberOfEntries: Int,
      evictInterval: JDuration,
      deleteInterval: JDuration,
      logDbCallsExceeding: FiniteDuration
  ): R2dbcProjectionSettings = R2dbcProjectionSettings(
    schema,
    offsetTable,
    timestampOffsetTable,
    managementTable,
    useConnectionFactory,
    timeWindow,
    keepNumberOfEntries,
    evictInterval,
    deleteInterval,
    logDbCallsExceeding,
    Dialect.Postgres
  )
}

// FIXME remove case class, and add `with` methods
final case class R2dbcProjectionSettings(
    schema: Option[String],
    offsetTable: String,
    timestampOffsetTable: String,
    managementTable: String,
    useConnectionFactory: String,
    timeWindow: JDuration,
    keepNumberOfEntries: Int,
    evictInterval: JDuration,
    deleteInterval: JDuration,
    logDbCallsExceeding: FiniteDuration,
    dialect: Dialect) {
  val offsetTableWithSchema: String = schema.map(_ + ".").getOrElse("") + offsetTable
  val timestampOffsetTableWithSchema: String = schema.map(_ + ".").getOrElse("") + timestampOffsetTable
  val managementTableWithSchema: String = schema.map(_ + ".").getOrElse("") + managementTable

  def isOffsetTableDefined: Boolean = offsetTable.nonEmpty
}
