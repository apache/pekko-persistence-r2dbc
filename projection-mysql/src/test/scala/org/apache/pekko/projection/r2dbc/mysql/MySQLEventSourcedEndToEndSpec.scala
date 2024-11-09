/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.projection.r2dbc.mysql

import org.apache.pekko
import pekko.persistence.r2dbc.internal.Sql
import pekko.projection.r2dbc.EventSourcedEndToEndSpec

class MySQLEventSourcedEndToEndSpec extends EventSourcedEndToEndSpec {
  override implicit lazy val sqlReplacements: Sql.Replacements = Sql.Replacements.None
}
