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

package org.apache.pekko.persistence.r2dbc.internal.oracle

import org.apache.pekko
import pekko.persistence.r2dbc.Dialect
import pekko.persistence.r2dbc.JournalSettings
import com.typesafe.config.ConfigFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import org.scalatest.TestSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class OracleUtilsSpec extends AnyWordSpec with TestSuite with Matchers {

  private def journalSettings(useAppTimestamp: Boolean, dbTimestampMonotonicIncreasing: Boolean): JournalSettings = {
    // without system properties, so that the dialect system property of the test run doesn't override the config
    val config = ConfigFactory
      .parseString(s"""
        pekko.persistence.r2dbc {
          dialect = oracle
          use-app-timestamp = $useAppTimestamp
          db-timestamp-monotonic-increasing = $dbTimestampMonotonicIncreasing
        }
        """)
      .withFallback(ConfigFactory.defaultReferenceUnresolved())
      .resolve()
    JournalSettings(config.getConfig("pekko.persistence.r2dbc.journal"))
  }

  "The oracle dialect" should {
    "be parsed from config" in {
      Dialect.fromString("oracle") shouldBe Dialect.Oracle
      Dialect.fromString("Oracle") shouldBe Dialect.Oracle
      journalSettings(useAppTimestamp = true, dbTimestampMonotonicIncreasing = true).dialect shouldBe Dialect.Oracle
    }

    "require app timestamps" in {
      OracleUtils.settingRequirements(journalSettings(useAppTimestamp = true, dbTimestampMonotonicIncreasing = true))

      intercept[IllegalArgumentException] {
        OracleUtils.settingRequirements(journalSettings(useAppTimestamp = false, dbTimestampMonotonicIncreasing = true))
      }.getMessage should include("use-app-timestamp")

      intercept[IllegalArgumentException] {
        OracleUtils.settingRequirements(journalSettings(useAppTimestamp = true, dbTimestampMonotonicIncreasing = false))
      }.getMessage should include("db-timestamp-monotonic-increasing")
    }
  }

  "OracleConnections.isOracle" should {
    "detect the Oracle driver from options and urls" in {
      OracleConnections.isOracle(
        ConnectionFactoryOptions.builder().option(ConnectionFactoryOptions.DRIVER, "oracle").build()) shouldBe true
      OracleConnections.isOracle(ConnectionFactoryOptions.parse("r2dbc:oracle://localhost:1521/FREEPDB1")) shouldBe true
      OracleConnections.isOracle(
        ConnectionFactoryOptions.parse("r2dbc:pool:oracle://localhost:1521/FREEPDB1")) shouldBe true
    }

    "not detect other drivers" in {
      OracleConnections.isOracle(
        ConnectionFactoryOptions.builder().option(ConnectionFactoryOptions.DRIVER, "postgres").build()) shouldBe false
      OracleConnections.isOracle(ConnectionFactoryOptions.parse("r2dbc:mysql://localhost:3306/db")) shouldBe false
    }
  }
}
