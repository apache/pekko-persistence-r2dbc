package org.apache.pekko.persistence.r2dbc.journal

import scala.collection.immutable.ListSet
import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.r2dbc.pool.ConnectionPool
import org.apache.pekko
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
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

    lazy val config: Config = {
      ConfigFactory
        .load(
          ConfigFactory
            .parseString(
              s"""
              $configKey {
                journal = $${pekko.persistence.r2dbc.journal}
                journal.shared = $${$configKey.shared}

                query = $${pekko.persistence.r2dbc.query}
                query.shared = $${$configKey.shared}

                snapshot = $${pekko.persistence.r2dbc.snapshot}
                snapshot.shared = $${$configKey.shared}

                shared = $${pekko.persistence.r2dbc.shared}
                shared = {
                  connection-factory {
                    database = "$database"
                  }
                }
              }
              """
            )
            .withFallback(TestConfig.unresolvedConfig)
        )
    }
  }
  object EventSourced {
    sealed trait Command
    case class Save(text: String, replyTo: ActorRef[Done]) extends Command
    case class ShowMeWhatYouGot(replyTo: ActorRef[String]) extends Command
    case object Stop extends Command
  }
}

class RuntimePluginConfigSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyFreeSpecLike
    with BeforeAndAfterEach
    with LogCapturing
    with Inside {
  import RuntimePluginConfigSpec._

  private val eventSourced1 = new EventSourced {
    override def configKey: String = "plugin1"
    override def database: String = "database1"
  }

  private val eventSourced2 = new EventSourced {
    override def configKey: String = "plugin2"
    override def database: String = "database2"
  }

  override protected def beforeEach(): Unit = {
    super.beforeAll()

    // TODO needs deduplication - very similar to TestDbLifecycle code
    ListSet(eventSourced1, eventSourced2).foreach { eventSourced =>
      val journalSettings: JournalSettings =
        new JournalSettings(eventSourced.config.getConfig(s"${eventSourced.configKey}.journal"))

      val snapshotSettings: SnapshotSettings =
        new SnapshotSettings(eventSourced.config.getConfig(s"${eventSourced.configKey}.snapshot"))

      val sharedSettings = SharedSettings(eventSourced.config.getConfig(s"${eventSourced.configKey}.shared"))

      val connectionFactoryProvider: ConnectionPool =
        ConnectionFactoryProvider(system)
          .connectionFactoryFor(sharedSettings.connectionFactorySettings)

      // this assumes that journal, state and store use same connection settings
      val r2dbcExecutor: R2dbcExecutor =
        new R2dbcExecutor(
          connectionFactoryProvider,
          LoggerFactory.getLogger(getClass),
          sharedSettings.logDbCallsExceeding)(system.executionContext, system)

      Await.result(
        r2dbcExecutor.updateOne("beforeAll delete")(
          _.createStatement(s"delete from ${journalSettings.journalTableWithSchema}")),
        10.seconds)
      Await.result(
        r2dbcExecutor.updateOne("beforeAll delete")(
          _.createStatement(s"delete from ${snapshotSettings.snapshotsTableWithSchema}")),
        10.seconds)

      connectionFactoryProvider.dispose()
    }
  }

  "The journal, query and snapshot store plugins must" - {

    "be possible to configure at runtime and use in multiple isolated instances" in {
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
  }
}
