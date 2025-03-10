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

package org.apache.pekko.persistence.r2dbc

import com.typesafe.config.ConfigFactory
import io.r2dbc.postgresql.client.SSLMode
import org.scalatest.TestSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class R2dbcSettingsSpec extends AnyWordSpec with TestSuite with Matchers {

  "Settings" should {
    "have table names with schema" in {
      val config = ConfigFactory.load(ConfigFactory.parseString("pekko.persistence.r2dbc.schema=s1"))
      val journalSettings = JournalSettings(config.getConfig("pekko.persistence.r2dbc.journal"))
      journalSettings.journalTableWithSchema shouldBe "s1.event_journal"
      val snapshotSettings = SnapshotSettings(config.getConfig("pekko.persistence.r2dbc.snapshot"))
      snapshotSettings.snapshotsTableWithSchema shouldBe "s1.snapshot"
      val stateSettings = StateSettings(config.getConfig("pekko.persistence.r2dbc.state"))
      stateSettings.durableStateTableWithSchema shouldBe "s1.durable_state"

      // by default connection is configured with options
      val connectionFactorySettings =
        ConnectionFactorySettings(config.getConfig("pekko.persistence.r2dbc.connection-factory"))
      connectionFactorySettings shouldBe a[ConnectionFactorySettings]
      connectionFactorySettings.urlOption should not be defined
    }

    "support connection settings build from url" in {
      val config =
        ConfigFactory
          .parseString("pekko.persistence.r2dbc.connection-factory.url=whatever-url")
          .withFallback(ConfigFactory.load())

      val settings = ConnectionFactorySettings(config.getConfig("pekko.persistence.r2dbc.connection-factory"))
      settings shouldBe a[ConnectionFactorySettings]
      settings.urlOption shouldBe defined
    }

    "support ssl-mode as enum name" in {
      val config = ConfigFactory
        .parseString("pekko.persistence.r2dbc.connection-factory.ssl.mode=VERIFY_FULL")
        .withFallback(ConfigFactory.load())
      val settings = ConnectionFactorySettings(config.getConfig("pekko.persistence.r2dbc.connection-factory"))
      settings.sslMode shouldBe "VERIFY_FULL"
      SSLMode.fromValue(settings.sslMode) shouldBe SSLMode.VERIFY_FULL
    }

    "support ssl-mode values in lower and dashes" in {
      val config = ConfigFactory
        .parseString("pekko.persistence.r2dbc.connection-factory.ssl.mode=verify-full")
        .withFallback(ConfigFactory.load())
      val settings = ConnectionFactorySettings(config.getConfig("pekko.persistence.r2dbc.connection-factory"))
      settings.sslMode shouldBe "verify-full"
      SSLMode.fromValue(settings.sslMode) shouldBe SSLMode.VERIFY_FULL
    }

    "allow to specify ConnectionFactoryOptions customizer" in {
      val config = ConfigFactory
        .parseString("pekko.persistence.r2dbc.connection-factory.connection-factory-options-customizer=fqcn")
        .withFallback(ConfigFactory.load())
      val settings = ConnectionFactorySettings(config.getConfig("pekko.persistence.r2dbc.connection-factory"))
      settings.connectionFactoryOptionsCustomizer shouldBe Some("fqcn")
    }
  }
}
