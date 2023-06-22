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
import pekko.actor.ExtendedActorSystem
import pekko.persistence.state.DurableStateStoreProvider
import com.typesafe.config.Config
import pekko.persistence.state.javadsl.{ DurableStateStore => JDurableStateStore }
import pekko.persistence.state.scaladsl.DurableStateStore

class R2dbcDurableStateStoreProvider[A](system: ExtendedActorSystem, config: Config, cfgPath: String)
    extends DurableStateStoreProvider {

  override def scaladslDurableStateStore(): DurableStateStore[Any] =
    new scaladsl.R2dbcDurableStateStore(system, config, cfgPath)

  override def javadslDurableStateStore(): JDurableStateStore[AnyRef] =
    new javadsl.R2dbcDurableStateStore[AnyRef](new scaladsl.R2dbcDurableStateStore(system, config, cfgPath))(
      system.dispatcher)
}
