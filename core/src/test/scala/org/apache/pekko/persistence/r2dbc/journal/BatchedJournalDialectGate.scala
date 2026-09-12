/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.persistence.r2dbc.journal

import org.apache.pekko
import pekko.persistence.r2dbc.TestConfig
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Outcome
import org.scalatest.Pending
import org.scalatest.TestSuite

/**
 * INTERNAL API
 */
private[r2dbc] trait BatchedJournalDialectGate extends TestSuite {

  protected def batchedJournalDialectSupported: Boolean = {
    val dialect = TestConfig.config.getString("pekko.persistence.r2dbc.dialect")
    dialect == "postgres" || dialect == "yugabyte"
  }

  override def withFixture(test: NoArgTest): Outcome =
    if (batchedJournalDialectSupported) super.withFixture(test)
    else Pending
}

/**
 * INTERNAL API
 */
private[r2dbc] trait BatchedJournalTckDialectGate extends BatchedJournalDialectGate with BeforeAndAfterEach {

  abstract override protected def beforeEach(): Unit =
    if (batchedJournalDialectSupported) super.beforeEach()
}
