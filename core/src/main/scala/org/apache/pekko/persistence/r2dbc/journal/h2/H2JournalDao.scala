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

package org.apache.pekko.persistence.r2dbc.journal.h2

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.JournalSettings
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import pekko.persistence.r2dbc.internal.h2.H2Utils
import pekko.persistence.r2dbc.journal.JournalDao
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Row

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class H2JournalDao(
    journalSettings: JournalSettings,
    connectionFactory: ConnectionFactory,
    closeCallsExceeding: Option[FiniteDuration] = None)(
    implicit ec: ExecutionContext, system: ActorSystem[?]
) extends JournalDao(journalSettings, connectionFactory, closeCallsExceeding) {
  // always app timestamp (db is same process) monotonic increasing
  H2Utils.settingRequirements(journalSettings)

  override lazy val timestampSql: String = "CURRENT_TIMESTAMP"
  override lazy val statementTimestampSql: String = "CURRENT_TIMESTAMP"

  override protected def tagsFromRow(row: Row): Set[String] =
    H2Utils.tagsFromDb(row, "tags")

  override val insertEventWithParameterTimestampSql: String =
    sql"INSERT INTO $journalTable " +
    "(slice, entity_type, persistence_id, seq_nr, writer, adapter_manifest, event_ser_id, event_ser_manifest, " +
    "event_payload, tags, meta_ser_id, meta_ser_manifest, meta_payload, db_timestamp) " +
    s"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
}
