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

package org.apache.pekko.persistence.r2dbc.journal

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransition

import scala.concurrent.duration._

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestData
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.persistence.r2dbc.journal.JournalDao.SerializedJournalRow
import pekko.persistence.r2dbc.query.scaladsl.QueryDao
import com.typesafe.config.ConfigFactory
import org.scalatest.Outcome
import org.scalatest.Pending
import org.scalatest.wordspec.AnyWordSpecLike

object DbTimestampDstSpec {
  val dialect: String = TestConfig.config.getString("pekko.persistence.r2dbc.dialect")

  /**
   * The time zone of the MySQL and MariaDB database containers, see `docker/docker-compose-*.yml`. It must observe
   * daylight saving time, so that it has a repeated local hour when the clocks are set back.
   */
  val DstZone: ZoneId = ZoneId.of("America/New_York")
}

/**
 * Timestamps in the local hour that is repeated when daylight saving time ends must be stored and read as the same
 * instants. The local time of the two instants is the same, so they are only stored correctly when the database
 * session doesn't use the server time zone for converting them.
 */
class DbTimestampDstSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {
  import DbTimestampDstSpec.DstZone

  override def typedSystem: ActorSystem[?] = system

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(10.seconds)

  override def withFixture(test: NoArgTest): Outcome =
    if (DbTimestampDstSpec.dialect == "mysql") Pending // https://github.com/apache/pekko-persistence-r2dbc/issues/533
    else super.withFixture(test)

  private val serverTimeZoneMatters = Set("mysql", "mariadb").contains(DbTimestampDstSpec.dialect)

  private lazy val journalDao =
    JournalDao.fromConfig(journalSettings, ConfigFactory.empty())(system, system.executionContext)

  private lazy val queryDao =
    QueryDao.fromConfig(querySettings, ConfigFactory.empty())(system, system.executionContext)

  // the next time the clocks are set back, when a local hour is repeated
  private lazy val clocksSetBack: ZoneOffsetTransition = {
    val rules = DstZone.getRules
    val now = Instant.now()
    Iterator
      .iterate(rules.nextTransition(now))(t => if (t == null) null else rules.nextTransition(t.getInstant))
      .takeWhile(t => t != null && t.getInstant.isBefore(now.plus(Duration.ofDays(366))))
      .find(_.isOverlap)
      .getOrElse(fail(
        s"$DstZone has no daylight saving time change within a year, it doesn't observe daylight saving time anymore. " +
        "Pick another time zone for the database containers in CI and for DstZone."))
  }

  // the same local time before and after the clocks are set back
  private lazy val beforeSetBack = clocksSetBack.getInstant.minus(Duration.ofMinutes(30))
  private lazy val afterSetBack = clocksSetBack.getInstant.plus(Duration.ofMinutes(30))

  private def serverLocalTime(instant: Instant): String = {
    val utc =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.ofInstant(instant, ZoneId.of("UTC")))
    r2dbcExecutor
      .selectOne("select server local time")(
        _.createStatement(s"SELECT CAST(CONVERT_TZ('$utc', '+00:00', 'SYSTEM') AS CHAR) AS local_time"),
        row => row.get("local_time", classOf[String]))
      .futureValue
      .get
  }

  private def storedEpochSeconds(persistenceId: String, seqNr: Long): Long =
    r2dbcExecutor
      .selectOne("select stored epoch seconds")(
        _.createStatement(
          s"SELECT CAST(FLOOR(UNIX_TIMESTAMP(db_timestamp)) AS SIGNED) AS epoch FROM ${journalSettings.journalTableWithSchema} " +
          "WHERE persistence_id = ? AND seq_nr = ?")
          .bind(0, persistenceId)
          .bind(1, seqNr),
        row => row.get("epoch", classOf[java.lang.Long]).longValue)
      .futureValue
      .get

  private def write(persistenceId: String, seqNr: Long, timestamp: Instant): Unit = {
    val slice = persistenceExt.sliceForPersistenceId(persistenceId)
    journalDao
      .writeEvents(
        Seq(SerializedJournalRow(slice, "TestEntity", persistenceId, seqNr, timestamp, timestamp,
          Some(Array[Byte](1)), 1, "", "writer", Set.empty, None)))
      .futureValue
  }

  "The database timestamps" should {
    "be in a time zone with a repeated local hour" in {
      LocalDateTime.ofInstant(beforeSetBack, DstZone) shouldBe LocalDateTime.ofInstant(afterSetBack, DstZone)

      if (serverTimeZoneMatters) {
        val expected = DateTimeFormatter
          .ofPattern("yyyy-MM-dd HH:mm:ss")
          .format(LocalDateTime.ofInstant(beforeSetBack, DstZone))
        withClue(s"The database server must run in the $DstZone time zone, see docker/docker-compose-*.yml.") {
          serverLocalTime(beforeSetBack) shouldBe expected
          serverLocalTime(afterSetBack) shouldBe expected
        }
      }
    }

    "store and read the instants of a repeated local hour" in {
      val persistenceId = nextPid("TestEntity")
      write(persistenceId, 1L, beforeSetBack)
      write(persistenceId, 2L, afterSetBack)

      if (serverTimeZoneMatters) {
        // the instants stored by the server, a round trip with the same wrong conversion in both directions passes
        storedEpochSeconds(persistenceId, 1L) shouldBe beforeSetBack.getEpochSecond
        storedEpochSeconds(persistenceId, 2L) shouldBe afterSetBack.getEpochSecond
      }

      queryDao.timestampOfEvent(persistenceId, 1L).futureValue shouldBe Some(beforeSetBack)
      queryDao.timestampOfEvent(persistenceId, 2L).futureValue shouldBe Some(afterSetBack)
      queryDao.loadEvent(persistenceId, 2L).futureValue.map(_.dbTimestamp) shouldBe Some(afterSetBack)
    }
  }
}
