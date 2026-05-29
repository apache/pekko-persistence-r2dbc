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

package org.apache.pekko.persistence.r2dbc.state

import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.persistence.CapabilityFlag
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestDbLifecycle

class R2dbcDurableStateStoreTCKSpec
    extends DurableStateStoreTCKSpec(TestConfig.config)
    with TestDbLifecycle {

  override def typedSystem: ActorSystem[_] = system.toTyped

  override protected def supportsDeleteWithRevisionCheck: CapabilityFlag = CapabilityFlag.on()
}
