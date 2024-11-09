/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.persistence.r2dbc.mysql.journal

import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import pekko.persistence.CapabilityFlag
import pekko.persistence.journal.JournalSpec
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.journal.R2dbcJournalSpec

class MySQLR2dbcJournalSpec extends JournalSpec(R2dbcJournalSpec.config) with TestDbLifecycle {
  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.off()
  override def typedSystem: ActorSystem[_] = system.toTyped
}

class MySQLR2dbcJournalWithMetaSpec extends JournalSpec(R2dbcJournalSpec.configWithMeta) with TestDbLifecycle {
  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.off()
  protected override def supportsMetadata: CapabilityFlag = CapabilityFlag.on()
  override def typedSystem: ActorSystem[_] = system.toTyped
}
