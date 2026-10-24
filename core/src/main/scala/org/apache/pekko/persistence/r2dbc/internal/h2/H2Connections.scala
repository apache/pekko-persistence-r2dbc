/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2022 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.internal.h2

import java.util.Locale

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.DispatcherSelector
import pekko.annotation.InternalApi
import com.typesafe.config.Config
import io.r2dbc.h2.H2ConnectionConfiguration
import io.r2dbc.h2.H2ConnectionFactory
import io.r2dbc.h2.H2ConnectionOption
import io.r2dbc.spi.ConnectionFactory

/**
 * INTERNAL API
 *
 * Creates the H2 connection factory from the `h2` section of the connection factory config. Only used when the
 * connection factory `driver` is `h2`, so that the optional H2 dependencies are not loaded otherwise.
 */
@InternalApi
private[r2dbc] object H2Connections {

  def create(config: Config): ConnectionFactory =
    new H2ConnectionFactory(configuration(config))

  def configuration(config: Config): H2ConnectionConfiguration = {
    // starting point for both url and regular configs,
    // to allow url to override anything but provide sane defaults
    val builder = H2ConnectionConfiguration.builder()
    val url = config.getString("url")
    if (url.nonEmpty) {
      builder.url(url)
    } else {
      val db = config.getString("database")
      config.getString("protocol").toLowerCase(Locale.ROOT) match {
        case "file"  => builder.file(db)
        case "mem"   => builder.inMemory(db)
        case unknown =>
          throw new IllegalArgumentException(s"Unknown H2 protocol [$unknown], supported protocols are [file, mem].")
      }

      val createSliceIndexes = config.getBoolean("create-slice-indexes")
      builder
        // create schema on first connect
        .property(H2ConnectionOption.INIT, dbSchema(config, createSliceIndexes, config.getString("additional-init")))
        // don't auto close connections
        .property(H2ConnectionOption.DB_CLOSE_DELAY, "-1")

      // workaround for https://github.com/akka/akka-projection/issues/992
      builder.option("OPTIMIZE_REUSE_RESULTS=FALSE")

      if (config.getBoolean("trace-logging"))
        // log to SLF4J instead of print to stdout, logger name will be 'h2database'
        builder.property(H2ConnectionOption.TRACE_LEVEL_FILE, "4")

      // Arbitrary config pass through/override for non-url setup
      config
        .getConfig("additional-options")
        .entrySet()
        .iterator()
        .asScala
        .foreach(entry => builder.option(s"${entry.getKey}=${entry.getValue.unwrapped()}"))
    }

    builder.build()
  }

  /**
   * The H2 R2DBC driver blocks in surprising places (Mono.toFuture in stmt.execute().asFuture()), so the DAOs run on
   * the configured dispatcher.
   */
  def executionContext(system: ActorSystem[?], connectionFactoryConfig: Config): ExecutionContext =
    system.dispatchers.lookup(
      DispatcherSelector.fromConfig(connectionFactoryConfig.getString("h2.use-dispatcher")))

  private def dbSchema(config: Config, createSliceIndexes: Boolean, additionalInit: String): String = {
    val schema = Option(config.getString("schema")).filter(_.trim.nonEmpty)
    def withSchema(table: String): String = schema.map(_ + ".").getOrElse("") + table
    val journalTableWithSchema = withSchema(config.getString("journal-table"))
    val snapshotTableWithSchema = withSchema(config.getString("snapshot-table"))
    val durableStateTableWithSchema = withSchema(config.getString("state-table"))

    val sliceIndexes =
      if (createSliceIndexes)
        Seq(
          s"CREATE INDEX IF NOT EXISTS ${journalTableWithSchema}_slice_idx " +
          s"ON $journalTableWithSchema(slice, entity_type, db_timestamp, seq_nr)",
          s"CREATE INDEX IF NOT EXISTS ${durableStateTableWithSchema}_slice_idx " +
          s"ON $durableStateTableWithSchema(slice, entity_type, db_timestamp, revision)")
      else Seq.empty[String]

    val schemaCreation = schema.map(s => s"CREATE SCHEMA IF NOT EXISTS $s").toSeq

    (schemaCreation ++ Seq(
      s"""CREATE TABLE IF NOT EXISTS $journalTableWithSchema (
        slice INT NOT NULL,
        entity_type VARCHAR(255) NOT NULL,
        persistence_id VARCHAR(255) NOT NULL,
        seq_nr BIGINT NOT NULL,
        db_timestamp timestamp with time zone NOT NULL,

        event_ser_id INTEGER NOT NULL,
        event_ser_manifest VARCHAR(255) NOT NULL,
        event_payload BYTEA NOT NULL,

        deleted BOOLEAN DEFAULT FALSE NOT NULL,
        writer VARCHAR(255) NOT NULL,
        adapter_manifest VARCHAR(255),
        tags TEXT ARRAY,

        meta_ser_id INTEGER,
        meta_ser_manifest VARCHAR(255),
        meta_payload BYTEA,

        PRIMARY KEY(persistence_id, seq_nr)
      )""",
      s"""CREATE TABLE IF NOT EXISTS $snapshotTableWithSchema (
        slice INT NOT NULL,
        entity_type VARCHAR(255) NOT NULL,
        persistence_id VARCHAR(255) NOT NULL,
        seq_nr BIGINT NOT NULL,
        write_timestamp BIGINT NOT NULL,
        ser_id INTEGER NOT NULL,
        ser_manifest VARCHAR(255) NOT NULL,
        snapshot BYTEA NOT NULL,
        meta_ser_id INTEGER,
        meta_ser_manifest VARCHAR(255),
        meta_payload BYTEA,

        PRIMARY KEY(persistence_id)
      )""",
      s"""CREATE TABLE IF NOT EXISTS $durableStateTableWithSchema (
        slice INT NOT NULL,
        entity_type VARCHAR(255) NOT NULL,
        persistence_id VARCHAR(255) NOT NULL,
        revision BIGINT NOT NULL,
        db_timestamp timestamp with time zone NOT NULL,

        state_ser_id INTEGER NOT NULL,
        state_ser_manifest VARCHAR(255),
        state_payload BYTEA NOT NULL,
        tags TEXT ARRAY,

        PRIMARY KEY(persistence_id, revision)
      )""") ++ sliceIndexes ++ (if (additionalInit.trim.nonEmpty) Seq(additionalInit) else Seq.empty[String]))
      .mkString(";") // r2dbc h2 driver replaces with '\;' as needed for INIT
  }
}
