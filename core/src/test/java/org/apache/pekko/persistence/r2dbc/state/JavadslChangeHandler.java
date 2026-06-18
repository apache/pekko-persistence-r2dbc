/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2022 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.state;

import org.apache.pekko.Done;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.persistence.query.DurableStateChange;
import org.apache.pekko.persistence.query.UpdatedDurableState;
import org.apache.pekko.persistence.r2dbc.session.javadsl.R2dbcSession;
import org.apache.pekko.persistence.r2dbc.state.javadsl.ChangeHandler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class JavadslChangeHandler implements ChangeHandler<String> {

  private final String insertSql;

  public JavadslChangeHandler(ActorSystem<?> system) {
    String dialect = system.settings().config().getString("pekko.persistence.r2dbc.dialect");
    // MySQL uses ? as parameter markers; Postgres/Yugabyte use $1, $2, $3
    if ("mysql".equals(dialect)) {
      this.insertSql = "insert into changes_test (pid, rev, value) values (?, ?, ?)";
    } else {
      this.insertSql = "insert into changes_test (pid, rev, value) values ($1, $2, $3)";
    }
  }

  @Override
  public CompletionStage<Done> process(R2dbcSession session, DurableStateChange<String> change) {
    if (change instanceof UpdatedDurableState<String> upd) {
      return session
          .updateOne(
              session
                  .createStatement(insertSql)
                  .bind(0, upd.persistenceId())
                  .bind(1, upd.revision())
                  .bind(2, upd.value()))
          .thenApply(n -> Done.getInstance());
    } else {
      return CompletableFuture.completedFuture(Done.getInstance());
    }
  }
}
