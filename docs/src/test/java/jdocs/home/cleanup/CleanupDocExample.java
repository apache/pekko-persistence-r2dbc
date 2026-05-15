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

package jdocs.home.cleanup;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

// #cleanup
import org.apache.pekko.persistence.query.PersistenceQuery;
import org.apache.pekko.persistence.query.javadsl.CurrentPersistenceIdsQuery;
import org.apache.pekko.persistence.r2dbc.cleanup.javadsl.EventSourcedCleanup;
import org.apache.pekko.persistence.r2dbc.query.javadsl.R2dbcReadJournal;

// #cleanup

public class CleanupDocExample {

  public static void example() {

    ActorSystem<?> system = ActorSystem.create(Behaviors.empty(), "Docs");

    // #cleanup
    CurrentPersistenceIdsQuery queries =
        PersistenceQuery.get(system)
            .getReadJournalFor(CurrentPersistenceIdsQuery.class, R2dbcReadJournal.Identifier());
    EventSourcedCleanup cleanup = new EventSourcedCleanup(system);

    // how many persistence ids to operate on in parallel
    int persistenceIdParallelism = 10;

    // for all persistence ids, delete all events before the snapshot
    queries
        .currentPersistenceIds()
        .mapAsync(persistenceIdParallelism, pid -> cleanup.cleanupBeforeSnapshot(pid))
        .run(system);

    // #cleanup
  }
}
