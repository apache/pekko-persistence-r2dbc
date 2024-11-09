/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.projection.r2dbc.mysql

import org.apache.pekko
import pekko.persistence.r2dbc.internal.Sql
import pekko.persistence.r2dbc.internal.Sql.ConfigurableInterpolation
import pekko.persistence.r2dbc.internal.Sql.Replacements
import pekko.projection.r2dbc.R2dbcProjectionSpec
import pekko.projection.r2dbc.R2dbcProjectionSpec.TestRepositoryProvider

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
