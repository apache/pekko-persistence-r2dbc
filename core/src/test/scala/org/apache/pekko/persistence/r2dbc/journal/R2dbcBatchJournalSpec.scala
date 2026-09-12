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
    with BatchedJournalTckDialectGate {
  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.off()
  override def typedSystem: ActorSystem[?] = system.toTyped
}
