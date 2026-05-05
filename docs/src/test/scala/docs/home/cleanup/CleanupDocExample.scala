/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.home.cleanup

import org.apache.pekko
import pekko.actor.typed.ActorSystem

//#cleanup
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.query.scaladsl.CurrentPersistenceIdsQuery
import pekko.persistence.r2dbc.cleanup.scaladsl.EventSourcedCleanup
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal

//#cleanup

object CleanupDocExample {

  implicit val system: ActorSystem[_] = ???

  //#cleanup
  val queries = PersistenceQuery(system).readJournalFor[CurrentPersistenceIdsQuery](R2dbcReadJournal.Identifier)
  val cleanup = new EventSourcedCleanup(system)

  // how many persistence ids to operate on in parallel
  val persistenceIdParallelism = 10

  // for all persistence ids, delete all events before the snapshot
  queries
    .currentPersistenceIds()
    .mapAsync(persistenceIdParallelism)(pid => cleanup.cleanupBeforeSnapshot(pid))
    .run()

  //#cleanup

}
