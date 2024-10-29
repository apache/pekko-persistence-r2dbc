/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.projection.r2dbc.mysql

import org.apache.pekko.persistence.r2dbc.internal.Sql
import org.apache.pekko.persistence.r2dbc.internal.Sql.ConfigurableInterpolation
import org.apache.pekko.persistence.r2dbc.internal.Sql.Replacements
import org.apache.pekko.projection.r2dbc.R2dbcProjectionSpec
import org.apache.pekko.projection.r2dbc.R2dbcProjectionSpec.TestRepositoryProvider

object MySQLR2dbcProjectionSpec {
  object MySQLTestRepositoryProvider extends TestRepositoryProvider.Default {
    override implicit lazy val sqlReplacements: Replacements = Sql.Replacements.None
    override lazy val upsertSql: String = sql"""
          INSERT INTO $table (id, concatenated)  VALUES (?, ?) AS excluded
          ON DUPLICATE KEY UPDATE
            id = excluded.id,
            concatenated = excluded.concatenated
         """
  }
}

class MySQLR2dbcProjectionSpec extends R2dbcProjectionSpec {
  override lazy val testRepositoryProvider: TestRepositoryProvider =
    MySQLR2dbcProjectionSpec.MySQLTestRepositoryProvider
}
