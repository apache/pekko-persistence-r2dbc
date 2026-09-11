/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.journal

import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.persistence.CapabilityFlag
import org.apache.pekko.persistence.journal.JournalSpec
import org.apache.pekko.persistence.r2dbc.TestDbLifecycle

object R2dbcBatchJournalSpec {
  val config: Config = ConfigFactory.parseString(
    """
      |pekko.persistence.r2dbc {
      |  use-app-timestamp = on
      |  db-timestamp-monotonic-increasing = on
      |  batched-journal {
      |    class = "org.apache.pekko.persistence.r2dbc.journal.R2dbcBatchJournal"
      |    use-app-timestamp = on
      |    db-timestamp-monotonic-increasing = on
      |  }
      |}
      |pekko.persistence.journal.plugin = "pekko.persistence.r2dbc.batched-journal"
      |""".stripMargin
  ).withFallback(R2dbcJournalSpec.config)
}

class R2dbcBatchJournalSpec extends JournalSpec(R2dbcBatchJournalSpec.config) with TestDbLifecycle
    with BatchedJournalDialectGate {
  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.off()
  override def typedSystem: ActorSystem[?] = system.toTyped
}
