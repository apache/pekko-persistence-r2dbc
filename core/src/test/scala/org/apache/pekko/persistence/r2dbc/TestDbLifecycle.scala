/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc

import scala.concurrent.Await
import scala.concurrent.duration._
import org.apache.pekko
import pekko.actor.typed.ActorRef
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.persistence.AtomicWrite
import pekko.persistence.JournalProtocol.WriteMessages
import pekko.persistence.Persistence
import pekko.persistence.PersistentRepr
import pekko.persistence.r2dbc.ConnectionFactorySettings
import pekko.persistence.r2dbc.internal.R2dbcExecutor
import pekko.persistence.r2dbc.internal.h2.H2Connections
import pekko.persistence.r2dbc.internal.h2.H2Utils
import pekko.persistence.r2dbc.journal.mysql.MySQLJournalDao
import com.typesafe.config.Config
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Row
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite
import org.slf4j.LoggerFactory

object TestDbLifecycle {

  /**
   * The H2 plugins create their tables when they are first used, which can be after a test harness deletes from them.
   */
  def createH2Tables(r2dbcExecutor: R2dbcExecutor, settings: ConnectionSettings*): Unit =
    settings.foreach { s =>
      if (s.dialect == Dialect.H2) {
        val tableDdl = s match {
          case j: JournalSettings  => H2Connections.journalTableDdl(j.journalTableWithSchema, createSliceIndexes = false)
          case s: SnapshotSettings =>
            H2Connections.snapshotTableDdl(s.snapshotsTableWithSchema, createSliceIndexes = false)
          case s: StateSettings =>
            H2Connections.durableStateTableDdl(s.durableStateTableWithSchema, createSliceIndexes = false)
          case _ => Nil
        }
        (H2Connections.schemaDdl(s.schema) ++ tableDdl).foreach { statement =>
          Await.result(r2dbcExecutor.executeDdl("create H2 table")(_.createStatement(statement)), 10.seconds)
        }
      }
    }
}

trait TestDbLifecycle extends BeforeAndAfterAll { this: Suite =>

  def typedSystem: ActorSystem[?]

  def testConfigPath: String = "pekko.persistence.r2dbc"

  private lazy val config: Config = typedSystem.settings.config

  lazy val journalSettings: JournalSettings = new JournalSettings(config.getConfig(testConfigPath + ".journal"))

  lazy val snapshotSettings: SnapshotSettings = new SnapshotSettings(config.getConfig(testConfigPath + ".snapshot"))

  lazy val stateSettings: StateSettings = new StateSettings(config.getConfig(testConfigPath + ".state"))

  lazy val querySettings: QuerySettings = QuerySettings(config.getConfig(testConfigPath + ".query"))

  // making sure that test harness does not initialize connection factory for the plugin that is being tested
  lazy val connectionFactoryProvider: ConnectionFactory =
    ConnectionFactoryProvider(typedSystem)
      .connectionFactoryFor("test.connection-factory",
        config.getConfig("pekko.persistence.r2dbc.connection-factory").atPath("test.connection-factory"))

  // this assumes that journal, snapshot store and state use same connection settings
  lazy val r2dbcExecutor: R2dbcExecutor = {
    val closeCallsExceeding =
      ConnectionFactorySettings.closeCallsExceeding(config.getConfig("pekko.persistence.r2dbc.connection-factory"))
    new R2dbcExecutor(
      connectionFactoryProvider,
      LoggerFactory.getLogger(getClass),
      journalSettings.logDbCallsExceeding,
      closeCallsExceeding)(typedSystem.executionContext, typedSystem)
  }

  lazy val persistenceExt: Persistence = Persistence(typedSystem)

  /** Read the tags column of a journal row into a set, honoring the per-dialect storage format. */
  protected def readTags(row: Row): Set[String] =
    journalSettings.dialect match {
      case Dialect.MySQL | Dialect.MariaDB =>
        MySQLJournalDao.tagsFromJson(row.get("tags", classOf[String]))
      case Dialect.H2 =>
        H2Utils.tagsFromDb(row, "tags")
      case Dialect.Postgres | Dialect.Yugabyte =>
        row.get("tags", classOf[Array[String]]) match {
          case null      => Set.empty[String]
          case tagsArray => tagsArray.toSet
        }
    }

  /** The classic WriteMessages envelope used by the batched journal specs for a single event. */
  protected def batchedJournalWrite(repr: PersistentRepr, replyTo: ActorRef[Any]): WriteMessages =
    WriteMessages(Seq(AtomicWrite(repr)), replyTo.toClassic, actorInstanceId = 1)

  protected def batchedJournalWrite(pid: String, seqNr: Long, event: String, replyTo: ActorRef[Any]): WriteMessages =
    batchedJournalWrite(PersistentRepr(event, seqNr, pid), replyTo)

  override protected def beforeAll(): Unit = {
    TestDbLifecycle.createH2Tables(r2dbcExecutor, journalSettings, snapshotSettings, stateSettings)
    Await.result(
      r2dbcExecutor.updateOne("beforeAll delete")(
        _.createStatement(s"delete from ${journalSettings.journalTableWithSchema}")),
      10.seconds)
    Await.result(
      r2dbcExecutor.updateOne("beforeAll delete")(
        _.createStatement(s"delete from ${snapshotSettings.snapshotsTableWithSchema}")),
      10.seconds)
    Await.result(
      r2dbcExecutor.updateOne("beforeAll delete")(
        _.createStatement(s"delete from ${stateSettings.durableStateTableWithSchema}")),
      10.seconds)
    super.beforeAll()
  }
}
