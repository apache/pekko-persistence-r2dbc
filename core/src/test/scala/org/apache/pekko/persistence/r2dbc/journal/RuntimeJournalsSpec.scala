package org.apache.pekko.persistence.r2dbc.journal

import scala.collection.immutable.ListSet
import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import io.r2dbc.pool.ConnectionPool
import org.apache.pekko
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

object RuntimeJournalsSpec {

  private object Actor {
    sealed trait Command
    case class Save(text: String, replyTo: ActorRef[Done]) extends Command
    case class ShowMeWhatYouGot(replyTo: ActorRef[String]) extends Command
    case object Stop extends Command

    def apply(persistenceId: String, journal: String): Behavior[Command] =
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
        .withJournalPluginId(s"$journal.journal")
        .withJournalPluginConfig(Some(config(journal)))
        .withSnapshotPluginId(s"$journal.snapshot")
        .withSnapshotPluginConfig(Some(config(journal)))

  }

  private def config(journal: String) = {
    // TODO add query config
    ConfigFactory.load(
      ConfigFactory.parseString(s"""
      $journal {
        journal = $${pekko.persistence.r2dbc.journal}
        journal.shared = $${$journal.shared}

        snapshot = $${pekko.persistence.r2dbc.snapshot}
        snapshot.shared = $${$journal.shared}

        shared = $${pekko.persistence.r2dbc.shared}
        shared = {
          connection-factory {
            database = "$journal"
          }
        }
      }
    """)
        .withFallback(TestConfig.unresolvedConfig)
    )
  }
}

class RuntimeJournalsSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyFreeSpecLike
    with BeforeAndAfterEach
    with LogCapturing
    with Inside {
  import RuntimeJournalsSpec._

  override protected def beforeEach(): Unit = {
    super.beforeAll()

    // TODO needs deduplication - very similar to TestDbLifecycle code
    ListSet("journal1", "journal2").foreach { journal =>
      val journalConfig = config(journal)

      val journalSettings: JournalSettings = new JournalSettings(journalConfig.getConfig(s"$journal.journal"))

      val snapshotSettings: SnapshotSettings = new SnapshotSettings(journalConfig.getConfig(s"$journal.snapshot"))

      val sharedSettings = SharedSettings(journalConfig.getConfig(s"$journal.shared"))

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
        val j1 = spawn(Actor("id1", "journal1"))
        val j2 = spawn(Actor("id1", "journal2"))
        j1 ! Actor.Save("j1m1", probe.ref)
        probe.receiveMessage()
        j2 ! Actor.Save("j2m1", probe.ref)
        probe.receiveMessage()
      }

      {
        def assertJournal(journal: String, expectedEvent: String) = {
          val ref = Persistence(system).journalFor(s"$journal.journal", config(journal))
          ref.tell(ReplayMessages(0, Long.MaxValue, Long.MaxValue, "id1", probe.ref.toClassic), probe.ref.toClassic)
          inside(probe.receiveMessage()) {
            case ReplayedMessage(persistentRepr) =>
              persistentRepr.persistenceId shouldBe "id1"
              persistentRepr.payload shouldBe expectedEvent
          }
          probe.expectMessage(RecoverySuccess(1))
        }

        assertJournal("journal1", "j1m1")
        assertJournal("journal2", "j2m1")
      }

      // TODO assert query

      {
        def assertSnapshot(journal: String, expectedShapshot: String) = {
          val ref = Persistence(system).snapshotStoreFor(s"$journal.snapshot", config(journal))
          ref.tell(LoadSnapshot("id1", SnapshotSelectionCriteria.Latest, Long.MaxValue),
            probe.ref.toClassic)
          inside(probe.receiveMessage()) {
            case LoadSnapshotResult(Some(SelectedSnapshot(_, snapshot)), _) =>
              snapshot shouldBe expectedShapshot
          }
        }

        assertSnapshot("journal1", "j1m1")
        assertSnapshot("journal2", "j2m1")
      }
    }
  }
}
