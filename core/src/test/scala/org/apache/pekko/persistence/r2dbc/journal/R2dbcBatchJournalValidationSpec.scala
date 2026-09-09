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
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.LoggingTestKit
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.persistence.Persistence
import pekko.persistence.r2dbc.TestConfig
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object R2dbcBatchJournalValidationSpec {

  // TestConfig.config is resolved, so its journal block has the reference.conf
  // substitutions frozen; the journal level settings must be overridden explicitly
  val zeroBatchSizeConfig: Config = ConfigFactory
    .parseString("""
      pekko.persistence.r2dbc {
        use-app-timestamp = on
        db-timestamp-monotonic-increasing = on
        batched-journal {
          class = "org.apache.pekko.persistence.r2dbc.journal.R2dbcBatchJournal"
          use-app-timestamp = on
          db-timestamp-monotonic-increasing = on
          max-batch-size = 0
        }
      }""")
    .withFallback(TestConfig.config)

  val appTimestampOffConfig: Config = ConfigFactory
    .parseString("""
      pekko.persistence.r2dbc {
        use-app-timestamp = off
        batched-journal {
          class = "org.apache.pekko.persistence.r2dbc.journal.R2dbcBatchJournal"
          use-app-timestamp = off
        }
      }""")
    .withFallback(TestConfig.config)

  val zeroQueueSizeConfig: Config = ConfigFactory
    .parseString("""
      pekko.persistence.r2dbc {
        use-app-timestamp = on
        db-timestamp-monotonic-increasing = on
        batched-journal {
          class = "org.apache.pekko.persistence.r2dbc.journal.R2dbcBatchJournal"
          use-app-timestamp = on
          db-timestamp-monotonic-increasing = on
          max-queue-size = 0
        }
      }""")
    .withFallback(TestConfig.config)

  val batchSizeExceedsQueueSizeConfig: Config = ConfigFactory
    .parseString("""
      pekko.persistence.r2dbc {
        use-app-timestamp = on
        db-timestamp-monotonic-increasing = on
        batched-journal {
          class = "org.apache.pekko.persistence.r2dbc.journal.R2dbcBatchJournal"
          use-app-timestamp = on
          db-timestamp-monotonic-increasing = on
          max-queue-size = 1
          max-batch-size = 2
        }
      }""")
    .withFallback(TestConfig.config)

  val monotonicIncreasingOffConfig: Config = ConfigFactory
    .parseString("""
      pekko.persistence.r2dbc {
        db-timestamp-monotonic-increasing = off
        batched-journal {
          class = "org.apache.pekko.persistence.r2dbc.journal.R2dbcBatchJournal"
          use-app-timestamp = on
          db-timestamp-monotonic-increasing = off
        }
      }""")
    .withFallback(TestConfig.config)

  val mysqlDialectConfig: Config = ConfigFactory
    .parseString("""
      pekko.persistence.r2dbc {
        use-app-timestamp = on
        db-timestamp-monotonic-increasing = on
        batched-journal {
          class = "org.apache.pekko.persistence.r2dbc.journal.R2dbcBatchJournal"
          use-app-timestamp = on
          db-timestamp-monotonic-increasing = on
          dialect = mysql
        }
      }""")
    .withFallback(TestConfig.config)
}

class R2dbcBatchJournalZeroBatchSizeSpec
    extends ScalaTestWithActorTestKit(R2dbcBatchJournalValidationSpec.zeroBatchSizeConfig)
    with AnyWordSpecLike
    with LogCapturing {

  "R2dbcBatchJournal validation" should {

    "fail fast when max-batch-size is less than 1" in {
      LoggingTestKit.error("max-batch-size must be at least 1").expect {
        Persistence(system).journalFor("pekko.persistence.r2dbc.batched-journal")
      }
    }
  }
}

class R2dbcBatchJournalZeroQueueSizeSpec
    extends ScalaTestWithActorTestKit(R2dbcBatchJournalValidationSpec.zeroQueueSizeConfig)
    with AnyWordSpecLike
    with LogCapturing {

  "R2dbcBatchJournal validation" should {

    "fail fast when max-queue-size is less than 1" in {
      LoggingTestKit.error("max-queue-size must be at least 1").expect {
        Persistence(system).journalFor("pekko.persistence.r2dbc.batched-journal")
      }
    }
  }
}

class R2dbcBatchJournalBatchSizeExceedsQueueSizeSpec
    extends ScalaTestWithActorTestKit(R2dbcBatchJournalValidationSpec.batchSizeExceedsQueueSizeConfig)
    with AnyWordSpecLike
    with LogCapturing {

  "R2dbcBatchJournal validation" should {

    "fail fast when max-batch-size exceeds max-queue-size" in {
      LoggingTestKit.error("max-batch-size must be less than or equal to `max-queue-size`").expect {
        Persistence(system).journalFor("pekko.persistence.r2dbc.batched-journal")
      }
    }
  }
}

class R2dbcBatchJournalAppTimestampOffSpec
    extends ScalaTestWithActorTestKit(R2dbcBatchJournalValidationSpec.appTimestampOffConfig)
    with AnyWordSpecLike
    with LogCapturing {

  "R2dbcBatchJournal validation" should {

    "fail fast when use-app-timestamp is off" in {
      LoggingTestKit.error("use-app-timestamp must be 'on'").expect {
        Persistence(system).journalFor("pekko.persistence.r2dbc.batched-journal")
      }
    }
  }
}

class R2dbcBatchJournalMonotonicIncreasingOffSpec
    extends ScalaTestWithActorTestKit(R2dbcBatchJournalValidationSpec.monotonicIncreasingOffConfig)
    with AnyWordSpecLike
    with LogCapturing {

  "R2dbcBatchJournal validation" should {

    "fail fast when db-timestamp-monotonic-increasing is off" in {
      LoggingTestKit.error("db-timestamp-monotonic-increasing must be 'on'").expect {
        Persistence(system).journalFor("pekko.persistence.r2dbc.batched-journal")
      }
    }
  }
}

class R2dbcBatchJournalMysqlDialectSpec
    extends ScalaTestWithActorTestKit(R2dbcBatchJournalValidationSpec.mysqlDialectConfig)
    with AnyWordSpecLike
    with LogCapturing {

  "R2dbcBatchJournal validation" should {

    "fail fast when the dialect does not support batching" in {
      LoggingTestKit.error("Batching is only supported for Postgres and Yugabyte").expect {
        Persistence(system).journalFor("pekko.persistence.r2dbc.batched-journal")
      }
    }
  }
}
