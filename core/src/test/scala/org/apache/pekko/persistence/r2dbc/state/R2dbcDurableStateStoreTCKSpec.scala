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

package org.apache.pekko.persistence.r2dbc.state

import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.persistence.CapabilityFlag
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.state.DurableStateStoreSpec

class R2dbcDurableStateStoreTCKSpec
    extends DurableStateStoreSpec(TestConfig.config)
    with TestDbLifecycle {

  override def typedSystem: ActorSystem[_] = system.toTyped

  override protected def supportsDeleteWithRevisionCheck: CapabilityFlag = CapabilityFlag.off()
}
