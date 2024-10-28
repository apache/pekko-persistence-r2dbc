/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.mysql.query

import org.apache.pekko.persistence.r2dbc.query.EventsBySliceBacktrackingSpec

class MySQLEventsBySliceBacktrackingSpec extends EventsBySliceBacktrackingSpec {
  override val insertEventSql = s"""
      INSERT INTO ${settings.journalTableWithSchema}
      (slice, entity_type, persistence_id, seq_nr, db_timestamp, writer, adapter_manifest, event_ser_id, event_ser_manifest, event_payload)
      VALUES (?, ?, ?, ?, ?, '', '', ?, '', ?)"""
}
