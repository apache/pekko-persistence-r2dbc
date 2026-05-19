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

package org.apache.pekko.persistence.r2dbc.journal

import org.apache.pekko
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorRef
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.typed.PersistenceId
import pekko.persistence.typed.scaladsl.Effect
import pekko.persistence.typed.scaladsl.EventSourcedBehavior
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object MultiPluginSpec {
  val config: Config = ConfigFactory
    .parseString("""
    // #default-config
    pekko.persistence.journal.plugin = "pekko.persistence.r2dbc.journal"
    pekko.persistence.snapshot-store.plugin = "pekko.persistence.r2dbc.snapshot"
    pekko.persistence.state.plugin = "pekko.persistence.r2dbc.state"
    // #default-config

    // #second-config
    second-r2dbc = ${pekko.persistence.r2dbc}
    second-r2dbc {
      connection-factory {
        # specific connection properties here
      }
      journal {
        # specific journal properties here
      }
      snapshot {
        # specific snapshot properties here
      }
      state {
        # specific durable state properties here
      }
      query {
        # specific query properties here
      }
    }
    // #second-config
    """)
    .withFallback(TestConfig.config)
    .resolve()

  object MyEntity {
    sealed trait Command
    final case class Persist(payload: String, replyTo: ActorRef[State]) extends Command
    type Event = String
    object State {
      def apply(): State = ""
    }
    type State = String

    def commandHandler(state: State, cmd: Command): Effect[Event, State] = {
      cmd match {
        case Persist(payload, replyTo) =>
          Effect
            .persist(payload)
            .thenReply(replyTo)(newState => newState)
      }
    }

    def eventHandler(state: State, evt: Event): State =
      state + evt

    def apply(persistenceId: PersistenceId): EventSourcedBehavior[Command, Event, State] = {
      // #withPlugins
      EventSourcedBehavior(persistenceId, emptyState = State(), commandHandler, eventHandler)
        .withJournalPluginId("second-r2dbc.journal")
        .withSnapshotPluginId("second-r2dbc.snapshot")
      // #withPlugins
    }
  }
}

class MultiPluginSpec
    extends ScalaTestWithActorTestKit(MultiPluginSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  import MultiPluginSpec.MyEntity

  override def typedSystem: ActorSystem[_] = system

  "Addition plugin config" should {

    "be supported for EventSourcedBehavior" in {
      val probe = createTestProbe[MyEntity.State]()
      val pid = PersistenceId.ofUniqueId(nextPid(nextEntityType()))
      val ref = spawn(MyEntity(pid))
      ref ! MyEntity.Persist("a", probe.ref)
      probe.expectMessage("a")
      ref ! MyEntity.Persist("b", probe.ref)
      probe.expectMessage("ab")
    }

  }
}
