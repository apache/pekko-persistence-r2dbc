/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.journal

import scala.concurrent.duration._

import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.persistence.CapabilityFlag
import pekko.persistence.journal.JournalPerfSpec
import pekko.persistence.r2dbc.TestDbLifecycle

object R2dbcJournalPerfSpec {
  val config = R2dbcJournalSpec.testConfig()
}

class R2dbcJournalPerfSpec extends JournalPerfSpec(R2dbcJournalPerfSpec.config) with TestDbLifecycle {
  override def eventsCount: Int = 200

  override def measurementIterations: Int = 2 // increase when testing for real

  override def awaitDurationMillis: Long = 60.seconds.toMillis

  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.off()

  override def typedSystem: ActorSystem[_] = system.toTyped
}
