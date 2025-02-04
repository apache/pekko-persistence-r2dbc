/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.journal

import scala.collection.immutable.ListSet
import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.r2dbc.pool.ConnectionPool
import org.apache.pekko
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.r2dbc.ConnectionFactorySettings
import org.apache.pekko.persistence.r2dbc.StateSettings
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.persistence.r2dbc.state.scaladsl.R2dbcDurableStateStore
import org.apache.pekko.persistence.state.scaladsl.GetObjectResult
import org.apache.pekko.stream.scaladsl.Sink
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorRef
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
import pekko.persistence.r2dbc.ConnectionFactoryProvider
import pekko.persistence.r2dbc.JournalSettings
import pekko.persistence.r2dbc.SharedSettings
import pekko.persistence.r2dbc.SnapshotSettings
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.internal.R2dbcExecutor
import pekko.persistence.typed.PersistenceId
import pekko.persistence.typed.scaladsl.Effect
import pekko.persistence.typed.scaladsl.EventSourcedBehavior
import pekko.persistence.typed.scaladsl.RetentionCriteria
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Inside
import org.scalatest.freespec.AnyFreeSpecLike
import org.slf4j.LoggerFactory

object RuntimePluginConfigSpec {

  trait EventSourced {
    import EventSourced._

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

                journal.$configKey.connection-factory = $${$configKey.connection-factory}
                journal.use-connection-factory = "$configKey.connection-factory"
                query.$configKey.connection-factory = $${$configKey.connection-factory}
                query.use-connection-factory = "$configKey.connection-factory"
                snapshot.$configKey.connection-factory = $${$configKey.connection-factory}
                snapshot.use-connection-factory = "$configKey.connection-factory"
              }
              """
            )
            .withFallback(TestConfig.unresolvedConfig)
        )

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
                state.$configKey.connection-factory {
                  database = "$database"
                }
                state.use-connection-factory = "$configKey.connection-factory"
              }
              """
            )
            .withFallback(TestConfig.unresolvedConfig)
        )

    // TODO refactor to use DurableState instead of plugin implementation directly once DurableState implements runtime config
    val store = new R2dbcDurableStateStore[String](
      typedSystem.toClassic.asInstanceOf[ExtendedActorSystem],
      config.getConfig(s"$configKey.state"),
      ""
    )
  }
}

class RuntimePluginConfigSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyFreeSpecLike
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

      // TODO provide unique ID for connection factory used by test harness
      val connectionFactoryProvider =
        ConnectionFactoryProvider(system)
          .connectionFactoryFor(journalSettings.useConnectionFactory, journalConfig)

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

      // TODO provide unique ID for connection factory used by test harness
      val connectionFactoryProvider =
        ConnectionFactoryProvider(system)
          .connectionFactoryFor(stateSettings.useConnectionFactory, stateConfig)

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

  "Should be possible to configure at runtime and use in multiple isolated instances when running " - {
    "journal, query and snapshot store plugins" in {
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

    "durable state plugin" in {
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
