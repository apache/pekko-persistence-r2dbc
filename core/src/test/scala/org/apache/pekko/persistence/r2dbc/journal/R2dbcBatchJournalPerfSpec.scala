/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.journal

import com.typesafe.config.Config

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import org.apache.pekko.persistence.CapabilityFlag
import org.apache.pekko.persistence.journal.JournalPerfSpec
import org.apache.pekko.persistence.r2dbc.TestDbLifecycle

import scala.concurrent.duration.DurationInt

object R2dbcBatchJournalPerfSpec {
  val config: Config = R2dbcBatchJournalSpec.config
}

class R2dbcBatchJournalPerfSpec extends JournalPerfSpec(R2dbcBatchJournalPerfSpec.config) with TestDbLifecycle {
  override def eventsCount: Int = 200

  override def measurementIterations: Int = 2 // increase when testing for real

  override def awaitDurationMillis: Long = 60.seconds.toMillis

  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.off()

  override def typedSystem: ActorSystem[?] = system.toTyped
}
