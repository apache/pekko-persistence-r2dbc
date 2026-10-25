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

package org.apache.pekko.persistence.r2dbc.internal

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.state.DurableStateStoreAdditionalColumnSpec
import pekko.persistence.r2dbc.state.scaladsl.R2dbcDurableStateStore
import pekko.persistence.state.DurableStateStoreRegistry
import pekko.persistence.state.scaladsl.GetObjectResult
import com.typesafe.config.ConfigFactory
import org.scalatest.Outcome
import org.scalatest.Pending
import org.scalatest.wordspec.AnyWordSpecLike

object H2AdditionalInitForSchemaSpec {

  val conf = ConfigFactory.load(
    ConfigFactory
      .parseString(s"""
      pekko.persistence.r2dbc.state {
        additional-columns {
           "CustomEntity" = ["${classOf[DurableStateStoreAdditionalColumnSpec.Column1].getName}"]
         }
      }
      pekko.persistence.r2dbc.connection-factory.h2 {
        protocol = "mem"
        database = "h2-additional-init-test-db"
      }
      // #additionalColumn
      pekko.persistence.r2dbc.state {
        h2-additional-ddl = "alter table durable_state add if not exists col1 varchar(256)"
      }
      // #additionalColumn
      """)
      .withFallback(TestConfig.unresolvedConfig))

}

class H2AdditionalInitForSchemaSpec
    extends ScalaTestWithActorTestKit(H2AdditionalInitForSchemaSpec.conf)
    with TestDbLifecycle
    with TestData
    with AnyWordSpecLike {

  private val isH2 = system.settings.config.getString("pekko.persistence.r2dbc.dialect") == "h2"

  override def withFixture(test: NoArgTest): Outcome =
    if (isH2) super.withFixture(test)
    else Pending

  override protected def beforeAll(): Unit =
    if (isH2) super.beforeAll()

  override def typedSystem: ActorSystem[?] = system

  private lazy val store = DurableStateStoreRegistry(testKit.system)
    .durableStateStoreFor[R2dbcDurableStateStore[String]](R2dbcDurableStateStore.Identifier)

  private def exists(whereCondition: String): Boolean =
    r2dbcExecutor
      .selectOne("count")(
        _.createStatement(s"select count(*) from durable_state where $whereCondition"),
        row => row.get(0, classOf[java.lang.Long]).longValue())
      .futureValue
      .contains(1)

  private def existsMatchingCol1(persistenceId: String, columnValue: String): Boolean =
    exists(s"persistence_id = '$persistenceId' and col1 = '$columnValue'")

  "The R2DBC durable state store" should {
    "save and retrieve a value in custom table with additional columns" in {
      val entityType = "CustomEntity"
      val persistenceId = nextPid(entityType)
      val value = "Genuinely Collaborative"

      store.upsertObject(persistenceId, 1L, value, "n/a").futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(value), 1L))

      existsMatchingCol1(persistenceId, value) should be(true)
    }
  }

}
