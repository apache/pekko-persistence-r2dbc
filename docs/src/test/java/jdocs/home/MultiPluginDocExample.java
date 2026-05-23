/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/**
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.home;

import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.javadsl.EventHandler;
import org.apache.pekko.persistence.typed.javadsl.EventSourcedBehavior;

public class MultiPluginDocExample {

  static
  // #withPlugins
  public class MyEntity extends EventSourcedBehavior<MyEntity.Command, MyEntity.Event, MyEntity.State> {
    // #withPlugins
    public MyEntity(PersistenceId persistenceId) {
      super(persistenceId);
    }

    interface Command {
    }

    interface Event {
    }

    static class State {
    }

    @Override
    public State emptyState() {
      return new State();
    }

    @Override
    public CommandHandler<Command, Event, State> commandHandler() {
      return newCommandHandlerBuilder().build();
    }

    @Override
    public EventHandler<State, Event> eventHandler() {
      return newEventHandlerBuilder().build();
    }

    // #withPlugins
    @Override
    public String journalPluginId() {
      return "second-r2dbc.journal";
    }

    @Override
    public String snapshotPluginId() {
      return "second-r2dbc.snapshot";
    }
  }
  // #withPlugins

}
