/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.persistence.Persistence
import pekko.testkit.TestKit

import org.scalatest.Assertion
import org.scalatest.Inspectors._
import org.scalatest.TestSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory

class R2dbcSettingsSpec extends AnyWordSpec with TestSuite with Matchers {

  private lazy val configV1 = ConfigFactory.parseResources("config-v1.conf")

  private val snapshotPluginId = "pekko.persistence.r2dbc.snapshot"

  private def withSnapshotStoreConfig(overrides: String)(check: Persistence => Assertion): Assertion = {
    // The native plugin config path is required, but no test needs a database connection.
    val h2Config = ConfigFactory.parseString("""
      pekko.persistence.r2dbc {
        dialect = h2
        connection-factory {
          driver = h2
          initial-size = 0
          h2.protocol = mem
        }
      }
      """)
    val config = ConfigFactory.parseString(overrides)
      .withFallback(h2Config)
      .withFallback(ConfigFactory.defaultReferenceUnresolved())
      .resolve()
    val system = ActorSystem("R2dbcSnapshotRetentionSettings", config)
    try check(Persistence(system))
    finally TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
  }

  private def onlyOneSnapshot(
      persistence: Persistence,
      pluginId: String = snapshotPluginId,
      overrides: Config = ConfigFactory.empty()): Boolean = {
    val snapshotStore = persistence.snapshotStoreFor(pluginId, overrides)
    persistence.configFor(snapshotStore).getBoolean("only-one-snapshot")
  }

  "The snapshot retention setting" should {
    "inherit the stock fallback and allow a plugin-specific opt-in" in {
      withSnapshotStoreConfig("""
        pekko.persistence.r2dbc.opt-in-snapshot = ${pekko.persistence.r2dbc.snapshot}
        pekko.persistence.r2dbc.opt-in-snapshot.only-one-snapshot = true
        """) { persistence =>
        onlyOneSnapshot(persistence) shouldBe false
        onlyOneSnapshot(persistence, "pekko.persistence.r2dbc.opt-in-snapshot") shouldBe true
      }
    }

    "inherit the configured application fallback and allow a plugin-specific opt-out" in {
      withSnapshotStoreConfig("""
        pekko.persistence.snapshot-store-plugin-fallback.only-one-snapshot = true
        pekko.persistence.r2dbc.opt-out-snapshot = ${pekko.persistence.r2dbc.snapshot}
        pekko.persistence.r2dbc.opt-out-snapshot.only-one-snapshot = false
        """) { persistence =>
        onlyOneSnapshot(persistence) shouldBe true
        onlyOneSnapshot(persistence, "pekko.persistence.r2dbc.opt-out-snapshot") shouldBe false
      }
    }

    "inherit the runtime snapshot plugin configuration fallback" in {
      withSnapshotStoreConfig("") { persistence =>
        val overrides =
          ConfigFactory.parseString("pekko.persistence.snapshot-store-plugin-fallback.only-one-snapshot = true")
        onlyOneSnapshot(persistence, overrides = overrides) shouldBe true
      }
    }
  }

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

    "parse the mariadb dialect" in {
      Dialect.fromString("mariadb") shouldBe Dialect.MariaDB
      Dialect.fromString("MariaDB") shouldBe Dialect.MariaDB
      // unresolved and without system properties, the dialect system property of the test run would override it
      val config = ConfigFactory
        .parseString("pekko.persistence.r2dbc.dialect=mariadb")
        .withFallback(ConfigFactory.defaultReferenceUnresolved())
        .resolve()
      JournalSettings(config.getConfig("pekko.persistence.r2dbc.journal")).dialect shouldBe Dialect.MariaDB
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
    }

    "support ssl-mode values in lower and dashes" in {
      val config = ConfigFactory
        .parseString("pekko.persistence.r2dbc.connection-factory.ssl.mode=verify-full")
        .withFallback(ConfigFactory.load())
      val settings = ConnectionFactorySettings(config.getConfig("pekko.persistence.r2dbc.connection-factory"))
      settings.sslMode shouldBe "verify-full"
    }

    "allow to specify ConnectionFactoryOptions customizer" in {
      val config = ConfigFactory
        .parseString("pekko.persistence.r2dbc.connection-factory.connection-factory-options-customizer=fqcn")
        .withFallback(ConfigFactory.load())
      val settings = ConnectionFactorySettings(config.getConfig("pekko.persistence.r2dbc.connection-factory"))
      settings.connectionFactoryOptionsCustomizer shouldBe Some("fqcn")
    }

    "not work when not merged with reference config for plugin config with v1 config" in {
      assertThrows[ConfigException.Missing](JournalSettings(configV1.getConfig("pekko.persistence.r2dbc.journal")))
      assertThrows[ConfigException.Missing](SnapshotSettings(configV1.getConfig("pekko.persistence.r2dbc.snapshot")))
      assertThrows[ConfigException.Missing](StateSettings(configV1.getConfig("pekko.persistence.r2dbc.state")))
    }

    "work when merged with reference config for plugin config with v1 config" in {
      val configWithReference = ConfigFactory.load(configV1)

      forEvery(
        List(
          JournalSettings(configWithReference.getConfig("pekko.persistence.r2dbc.journal")),
          SnapshotSettings(configWithReference.getConfig("pekko.persistence.r2dbc.snapshot")),
          StateSettings(configWithReference.getConfig("pekko.persistence.r2dbc.state"))
        )
      ) { settings =>
        settings.useConnectionFactory shouldBe "pekko.persistence.r2dbc.connection-factory"
      }
    }
  }
}
