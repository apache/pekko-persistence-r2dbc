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

package org.apache.pekko.persistence.r2dbc.query

import org.apache.pekko
import pekko.actor.ExtendedActorSystem
import pekko.persistence.query.ReadJournalProvider
import com.typesafe.config.Config

final class R2dbcReadJournalProvider(system: ExtendedActorSystem, config: Config, cfgPath: String)
    extends ReadJournalProvider {
  override val scaladslReadJournal: scaladsl.R2dbcReadJournal =
    new scaladsl.R2dbcReadJournal(system, config, cfgPath)

  override val javadslReadJournal: javadsl.R2dbcReadJournal = new javadsl.R2dbcReadJournal(scaladslReadJournal)
}
