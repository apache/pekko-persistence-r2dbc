/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pekko.persistence.r2dbc.journal

import scala.collection.immutable.ListSet
import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.Done
import pekko.actor.ExtendedActorSystem
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorRef
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.Behavior
import pekko.actor.typed.scaladsl.adapter._
import pekko.persistence.JournalProtocol.RecoverySuccess
import pekko.persistence.JournalProtocol.ReplayMessages
import pekko.persistence.JournalProtocol.ReplayedMessage
import pekko.persistence.Persistence
import pekko.persistence.SelectedSnapshot
import pekko.persistence.SnapshotProtocol.LoadSnapshot
import pekko.persistence.SnapshotProtocol.LoadSnapshotResult
import pekko.persistence.SnapshotSelectionCriteria
import pekko.persistence.query.PersistenceQuery
import pekko.persistence.r2dbc.ConnectionFactoryProvider
import pekko.persistence.r2dbc.JournalSettings
import pekko.persistence.r2dbc.SnapshotSettings
import pekko.persistence.r2dbc.StateSettings
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.internal.R2dbcExecutor
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.persistence.r2dbc.state.scaladsl.R2dbcDurableStateStore
import pekko.persistence.state.scaladsl.GetObjectResult
import pekko.persistence.typed.PersistenceId
import pekko.persistence.typed.scaladsl.Effect
import pekko.persistence.typed.scaladsl.EventSourcedBehavior
import pekko.persistence.typed.scaladsl.RetentionCriteria
import pekko.stream.scaladsl.Sink
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Inside
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory

object RuntimePluginConfigSpec {

  trait EventSourced {
    import EventSourced._

    def configKey: String
    def database: String

    lazy val unresolvedConfig = ConfigFactory
      .parseString(
        s"""
              $configKey = $${pekko.persistence.r2dbc}
              $configKey = {
                connection-factory {
                  database = "$database"
                }

                journal.$configKey.connection-factory = $${$configKey.connection-factory}
                journal.use-connection-factory = "$configKey.connection-factory"
                query.$configKey.connection-factory = $${$configKey.connection-factory}
                query.use-connection-factory = "$configKey.connection-factory"
                snapshot.$configKey.connection-factory = $${$configKey.connection-factory}
                snapshot.use-connection-factory = "$configKey.connection-factory"
              }
              """
      )

    lazy val config: Config = ConfigFactory.load(unresolvedConfig.withFallback(TestConfig.unresolvedConfig))

    def apply(persistenceId: String): Behavior[Command] =
      EventSourcedBehavior[Command, String, String](
        PersistenceId.ofUniqueId(persistenceId),
        "",
        (state, cmd) =>
          cmd match {
            case Save(text, replyTo) =>
              Effect.persist(text).thenRun(_ => replyTo ! Done)
            case ShowMeWhatYouGot(replyTo) =>
              replyTo ! state
              Effect.none
            case Stop =>
              Effect.stop()
          },
        (state, evt) => Seq(state, evt).filter(_.nonEmpty).mkString("|"))
        .withRetention(RetentionCriteria.snapshotEvery(1, Int.MaxValue))
        .withJournalPluginId(s"$configKey.journal")
        .withJournalPluginConfig(Some(config))
        .withSnapshotPluginId(s"$configKey.snapshot")
        .withSnapshotPluginConfig(Some(config))
  }
  object EventSourced {
    sealed trait Command
    case class Save(text: String, replyTo: ActorRef[Done]) extends Command
    case class ShowMeWhatYouGot(replyTo: ActorRef[String]) extends Command
    case object Stop extends Command
  }

  trait DurableState {
    def typedSystem: ActorSystem[_]
    def configKey: String
    def database: String

    lazy val config: Config =
      ConfigFactory
        .load(
          ConfigFactory
            .parseString(
              s"""
              $configKey = $${pekko.persistence.r2dbc}
              $configKey = {
                connection-factory {
                  database = "$database"
                }

                state.$configKey.connection-factory = $${$configKey.connection-factory}
                state.use-connection-factory = "$configKey.connection-factory"
              }
              """
            )
            .withFallback(TestConfig.unresolvedConfig)
        )

    val store = new R2dbcDurableStateStore[String](
      typedSystem.toClassic.asInstanceOf[ExtendedActorSystem],
      config.getConfig(s"$configKey.state"),
      ""
    )
  }
}

class RuntimePluginConfigSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with BeforeAndAfterEach
    with LogCapturing
    with Inside {
  import RuntimePluginConfigSpec._

  private lazy val eventSourced1 = new EventSourced {
    override def configKey: String = "plugin1"
    override def database: String = "database1"
  }
  private lazy val eventSourced2 = new EventSourced {
    override def configKey: String = "plugin2"
    override def database: String = "database2"
  }

  private lazy val state1 = new DurableState {
    override def typedSystem: ActorSystem[_] = system
    override def configKey: String = "plugin1"
    override def database: String = "database1"
  }
  private lazy val state2 = new DurableState {
    override def typedSystem: ActorSystem[_] = system
    override def configKey: String = "plugin2"
    override def database: String = "database2"
  }

  override protected def beforeEach(): Unit = {
    super.beforeAll()

    ListSet(eventSourced1, eventSourced2).foreach { eventSourced =>
      val journalConfig = eventSourced.config.getConfig(s"${eventSourced.configKey}.journal")
      val journalSettings: JournalSettings = JournalSettings(journalConfig)

      val snapshotSettings: SnapshotSettings =
        SnapshotSettings(eventSourced.config.getConfig(s"${eventSourced.configKey}.snapshot"))

      // making sure that test harness does not initialize connection factory for the plugin that is being tested
      val connectionFactoryProvider =
        ConnectionFactoryProvider(system)
          .connectionFactoryFor(s"test.${eventSourced.configKey}.connection-factory",
            journalConfig.getConfig(journalSettings.useConnectionFactory).atPath(
              s"test.${eventSourced.configKey}.connection-factory"))

      // this assumes that journal, snapshot store and state use same connection settings
      val r2dbcExecutor: R2dbcExecutor =
        new R2dbcExecutor(
          connectionFactoryProvider,
          LoggerFactory.getLogger(getClass),
          journalSettings.logDbCallsExceeding)(system.executionContext, system)

      Await.result(
        r2dbcExecutor.updateOne("beforeAll delete")(
          _.createStatement(s"delete from ${journalSettings.journalTableWithSchema}")),
        10.seconds)
      Await.result(
        r2dbcExecutor.updateOne("beforeAll delete")(
          _.createStatement(s"delete from ${snapshotSettings.snapshotsTableWithSchema}")),
        10.seconds)
    }

    ListSet(state1, state2).foreach { state =>
      val stateConfig = state.config.getConfig(s"${state.configKey}.state")
      val stateSettings: StateSettings = StateSettings(stateConfig)

      // making sure that test harness does not initialize connection factory for the plugin that is being tested
      val connectionFactoryProvider =
        ConnectionFactoryProvider(system)
          .connectionFactoryFor(s"test.${state.configKey}.connection-factory",
            state.config.getConfig(stateSettings.useConnectionFactory).atPath(
              s"test.${state.configKey}.connection-factory"))

      val r2dbcExecutor: R2dbcExecutor =
        new R2dbcExecutor(
          connectionFactoryProvider,
          LoggerFactory.getLogger(getClass),
          stateSettings.logDbCallsExceeding)(system.executionContext, system)

      Await.result(
        r2dbcExecutor.updateOne("beforeAll delete")(
          _.createStatement(s"delete from ${stateSettings.durableStateTableWithSchema}")),
        10.seconds)
    }
  }

  "Runtime plugin config" should {
    "work for journal, query and snapshot store plugins" in {
      val probe = createTestProbe[Any]()

      {
        // one actor in each journal with same id
        val j1 = spawn(eventSourced1("id1"))
        val j2 = spawn(eventSourced2("id1"))
        j1 ! EventSourced.Save("j1m1", probe.ref)
        probe.receiveMessage()
        j2 ! EventSourced.Save("j2m1", probe.ref)
        probe.receiveMessage()
      }

      {
        def assertJournal(eventSourced: EventSourced, expectedEvent: String) = {
          val ref = Persistence(system).journalFor(s"${eventSourced.configKey}.journal", eventSourced.config)
          ref.tell(ReplayMessages(0, Long.MaxValue, Long.MaxValue, "id1", probe.ref.toClassic), probe.ref.toClassic)
          inside(probe.receiveMessage()) {
            case ReplayedMessage(persistentRepr) =>
              persistentRepr.persistenceId shouldBe "id1"
              persistentRepr.payload shouldBe expectedEvent
          }
          probe.expectMessage(RecoverySuccess(1))
        }

        assertJournal(eventSourced1, "j1m1")
        assertJournal(eventSourced2, "j2m1")
      }

      {
        def assertQuery(eventSourced: EventSourced, expectedEvent: String) = {
          val readJournal =
            PersistenceQuery(system).readJournalFor[R2dbcReadJournal](s"${eventSourced.configKey}.query",
              eventSourced.config)
          val events = readJournal.currentEventsByPersistenceId("id1", 0, Long.MaxValue)
            .map(_.event)
            .runWith(Sink.seq).futureValue
          events should contain theSameElementsAs Seq(expectedEvent)
        }

        assertQuery(eventSourced1, "j1m1")
        assertQuery(eventSourced2, "j2m1")
      }

      {
        def assertSnapshot(eventSourced: EventSourced, expectedShapshot: String) = {
          val ref = Persistence(system).snapshotStoreFor(s"${eventSourced.configKey}.snapshot", eventSourced.config)
          ref.tell(LoadSnapshot("id1", SnapshotSelectionCriteria.Latest, Long.MaxValue),
            probe.ref.toClassic)
          inside(probe.receiveMessage()) {
            case LoadSnapshotResult(Some(SelectedSnapshot(_, snapshot)), _) =>
              snapshot shouldBe expectedShapshot
          }
        }

        assertSnapshot(eventSourced1, "j1m1")
        assertSnapshot(eventSourced2, "j2m1")
      }
    }

    "work for durable state plugin" in {
      // persist data on both plugins
      state1.store.upsertObject("id1", 1, "j1m1", "").futureValue
      state2.store.upsertObject("id1", 1, "j2m1", "").futureValue

      def assertState(state: DurableState, expectedState: String) = {
        inside(state.store.getObject("id1").futureValue) {
          case GetObjectResult(Some(value), revision) =>
            value shouldBe expectedState
            revision shouldBe 1
        }
      }

      assertState(state1, "j1m1")
      assertState(state2, "j2m1")
    }
  }
}
