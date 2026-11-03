/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.persistence.r2dbc

import java.time.Instant

import scala.concurrent.duration._

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import org.scalatest.Outcome
import org.scalatest.Pending
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * MySQL and MariaDB convert TIMESTAMP values with the session time zone, and the drivers convert Instant with the
 * time zone the plugin configures, which is only correct when the session time zone is UTC. Run against a server with
 * a non-UTC time zone to verify that the session time zone is set.
 */
class DbTimeZoneSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with LogCapturing {

  override def typedSystem: ActorSystem[?] = system

  override def withFixture(test: NoArgTest): Outcome =
    TestConfig.dialect match {
      case Dialect.MySQL | Dialect.MariaDB                  => super.withFixture(test)
      case Dialect.Postgres | Dialect.Yugabyte | Dialect.H2 => Pending // the session time zone doesn't matter
    }

  "Database connections" should {
    "use the UTC session time zone" in {
      // the session time zone can also be SYSTEM when the server time zone is UTC, so the offset is checked
      r2dbcExecutor
        .selectOne("select session time zone offset")(
          _.createStatement("SELECT TIMESTAMPDIFF(MINUTE, UTC_TIMESTAMP(), NOW()) AS offset_minutes"),
          row => row.get("offset_minutes", classOf[java.lang.Long]).longValue)
        .futureValue shouldBe Some(0L)
    }

    "bind Instant as the same point in time" in {
      val instant = Instant.parse("2026-10-27T11:19:11Z")
      r2dbcExecutor
        .selectOne("select epoch seconds")(
          _.createStatement("SELECT CAST(UNIX_TIMESTAMP(?) AS SIGNED) AS epoch").bind(0, instant),
          row => row.get("epoch", classOf[java.lang.Long]).longValue)
        .futureValue shouldBe Some(instant.getEpochSecond)
    }

    "read database time as Instant" in {
      val dbNow = r2dbcExecutor
        .selectOne("select now")(
          _.createStatement("SELECT NOW(6) AS now"),
          row => row.get("now", classOf[Instant]))
        .futureValue
        .get
      val now = Instant.now()
      dbNow.isAfter(now.minusSeconds(60)) shouldBe true
      dbNow.isBefore(now.plusSeconds(60)) shouldBe true
    }
  }

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(10.seconds)
}
