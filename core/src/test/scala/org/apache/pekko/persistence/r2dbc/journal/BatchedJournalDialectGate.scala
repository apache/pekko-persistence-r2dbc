/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.journal

import org.apache.pekko
import pekko.persistence.r2dbc.TestConfig
import org.scalatest.{ Outcome, Pending }
import org.scalatest.TestSuite

/**
 * INTERNAL API
 */
private[r2dbc] trait BatchedJournalDialectGate extends TestSuite {

  private val dialect = TestConfig.config.getString("pekko.persistence.r2dbc.dialect")

  override def withFixture(test: NoArgTest): Outcome =
    if (dialect == "mysql") Pending
    else super.withFixture(test)
}
