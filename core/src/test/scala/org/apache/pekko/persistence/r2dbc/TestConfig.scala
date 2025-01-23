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

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object TestConfig {

  lazy val unresolvedConfig: Config = {
    val defaultConfig = ConfigFactory.load()
    val dialect = defaultConfig.getString("pekko.persistence.r2dbc.shared.dialect")

    val dialectConfig = dialect match {
      case "postgres" =>
        ConfigFactory.parseString("""
          pekko.persistence.r2dbc.shared.connection-factory {
            driver = "postgres"
            host = "localhost"
            port = 5432
            user = "postgres"
            password = "postgres"
            database = "postgres"
          }
          """)
      case "yugabyte" =>
        ConfigFactory.parseString("""
          pekko.persistence.r2dbc.shared.connection-factory {
            driver = "postgres"
            host = "localhost"
            port = 5433
            user = "yugabyte"
            password = "yugabyte"
            database = "yugabyte"
          }
          """)
      case "mysql" =>
        ConfigFactory.parseString("""
          pekko.persistence.r2dbc.shared {
            connection-factory {
              driver = "mysql"
              host = "localhost"
              port = 3306
              user = "root"
              password = "root"
              database = "mysql"
            }
            db-timestamp-monotonic-increasing = on
            use-app-timestamp = on
          }
          """)
    }

    // reducing pool size in tests because connection factories between plugins are not shared
    val poolConfig =
      ConfigFactory.parseString("""
          pekko.persistence.r2dbc.shared.connection-factory {
            initial-size = 2
            max-size = 4
          }
          """)

    dialectConfig.withFallback(poolConfig).withFallback(ConfigFactory.parseString("""
    pekko.loglevel = DEBUG
    pekko.persistence.journal.plugin = "pekko.persistence.r2dbc.journal"
    pekko.persistence.snapshot-store.plugin = "pekko.persistence.r2dbc.snapshot"
    pekko.persistence.state.plugin = "pekko.persistence.r2dbc.state"
    pekko.persistence.r2dbc {
      shared {
        refresh-interval = 1s
      }
    }
    pekko.actor {
      serialization-bindings {
        "org.apache.pekko.persistence.r2dbc.CborSerializable" = jackson-cbor
      }
    }
    pekko.actor.testkit.typed.default-timeout = 10s
    """))
  }

  // FIXME ideally every dependant that combines this config with other configs should load/resolve at their callsites
  lazy val config: Config = ConfigFactory.load(unresolvedConfig)

  val backtrackingDisabledConfig: Config =
    ConfigFactory.parseString("pekko.persistence.r2dbc.shared.backtracking.enabled = off")
}
