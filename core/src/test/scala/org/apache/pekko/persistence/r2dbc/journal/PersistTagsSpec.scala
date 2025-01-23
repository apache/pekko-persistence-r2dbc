/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.journal

import scala.concurrent.duration._
import org.apache.pekko
import org.apache.pekko.persistence.r2dbc.JournalSettings
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.TestActors.Persister
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.typed.PersistenceId
import org.scalatest.wordspec.AnyWordSpecLike

class PersistTagsSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[_] = system
  private val settings = JournalSettings(system.settings.config.getConfig("pekko.persistence.r2dbc.journal"))

  case class Row(pid: String, seqNr: Long, tags: Set[String])

  private lazy val dialect = system.settings.config.getString("pekko.persistence.r2dbc.journal.shared.dialect")

  private lazy val testEnabled: Boolean = {
    // tags are not implemented for MySQL
    dialect != "mysql"
  }

  "Persist tags" should {
    if (!testEnabled) {
      info(s"PersistTagsSpec not enabled for $dialect")
      pending
    }

    "be the same for events stored in same transaction" in {
      val numberOfEntities = 9
      val entityType = nextEntityType()

      val entities = (0 until numberOfEntities).map { n =>
        val persistenceId = PersistenceId(entityType, s"p$n")
        val tags = Set(entityType, s"tag-p$n")
        spawn(Persister(persistenceId, tags), s"p$n")
      }

      entities.foreach { ref =>
        ref ! Persister.Persist("e1")
      }

      val pingProbe = createTestProbe[Done]()
      entities.foreach { ref =>
        ref ! Persister.Ping(pingProbe.ref)
      }
      pingProbe.receiveMessages(entities.size, 20.seconds)

      val rows =
        r2dbcExecutor
          .select[Row]("test")(
            connection => connection.createStatement(s"select * from ${settings.journalTableWithSchema}"),
            row => {
              val tags = row.get("tags", classOf[Array[String]]) match {
                case null      => Set.empty[String]
                case tagsArray => tagsArray.toSet
              }
              Row(
                pid = row.get("persistence_id", classOf[String]),
                seqNr = row.get[java.lang.Long]("seq_nr", classOf[java.lang.Long]),
                tags)
            })
          .futureValue

      rows.foreach { case Row(pid, _, tags) =>
        withClue(s"pid [$pid}]: ") {
          tags shouldBe Set(PersistenceId.extractEntityType(pid), s"tag-${PersistenceId.extractEntityId(pid)}")
        }
      }
    }

  }
}
