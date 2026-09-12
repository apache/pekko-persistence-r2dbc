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

class R2dbcBatchJournalPerfSpec extends JournalPerfSpec(R2dbcBatchJournalPerfSpec.config) with TestDbLifecycle
    with BatchedJournalTckDialectGate {
  override def eventsCount: Int = 200

  override def measurementIterations: Int = 2 // increase when testing for real

  override def awaitDurationMillis: Long = 60.seconds.toMillis

  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.off()

  override def typedSystem: ActorSystem[?] = system.toTyped
}
