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

package org.apache.pekko.persistence.r2dbc.internal.mariadb

import java.time.Instant
import java.time.LocalDateTime

import scala.collection.mutable

import org.apache.pekko
import pekko.persistence.r2dbc.internal.ConnectionAdapter
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Result
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import io.r2dbc.spi.Statement
import org.reactivestreams.Publisher
import org.scalatest.TestSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MariaDBConnectionsSpec extends AnyWordSpec with TestSuite with Matchers {

  private val instant = Instant.parse("2026-10-27T11:19:11.123456Z")
  private val utcLocalDateTime = LocalDateTime.parse("2026-10-27T11:19:11.123456")

  private class RecordingStatement extends Statement {
    val bound = mutable.Map.empty[Any, Any]
    val boundNull = mutable.Map.empty[Any, Class[?]]
    override def add(): Statement = this
    override def bind(index: Int, value: Any): Statement = { bound(index) = value; this }
    override def bind(name: String, value: Any): Statement = { bound(name) = value; this }
    override def bindNull(index: Int, tpe: Class[?]): Statement = { boundNull(index) = tpe; this }
    override def bindNull(name: String, tpe: Class[?]): Statement = { boundNull(name) = tpe; this }
    override def execute(): Publisher[? <: Result] = throw new UnsupportedOperationException
  }

  // a row like the MariaDB driver returns, which only supports LocalDateTime for timestamps
  private class LocalDateTimeRow(value: LocalDateTime) extends Row {
    override def getMetadata: RowMetadata = throw new UnsupportedOperationException
    override def get[T](index: Int, tpe: Class[T]): T = get("col", tpe)
    override def get[T](name: String, tpe: Class[T]): T =
      if (tpe == classOf[LocalDateTime]) value.asInstanceOf[T]
      else if (tpe == classOf[String]) "text".asInstanceOf[T]
      else throw new IllegalArgumentException(s"No decoder for type ${tpe.getName}")
  }

  "MariaDBConnections" should {
    "bind Instant as UTC LocalDateTime" in {
      val recording = new RecordingStatement
      new ConnectionAdapter.AdaptedStatement(recording, MariaDBConnections)
        .bind(0, instant)
        .bind("ts", instant)
        .bind(1, "text")
      recording.bound(0) shouldBe utcLocalDateTime
      recording.bound("ts") shouldBe utcLocalDateTime
      recording.bound(1) shouldBe "text"
    }

    "bind null Instant as LocalDateTime" in {
      val recording = new RecordingStatement
      new ConnectionAdapter.AdaptedStatement(recording, MariaDBConnections)
        .bindNull(0, classOf[Instant])
        .bindNull("ts", classOf[Instant])
        .bindNull(1, classOf[String])
      recording.boundNull(0) shouldBe classOf[LocalDateTime]
      recording.boundNull("ts") shouldBe classOf[LocalDateTime]
      recording.boundNull(1) shouldBe classOf[String]
    }

    "read UTC LocalDateTime as Instant" in {
      val row = new ConnectionAdapter.AdaptedRow(new LocalDateTimeRow(utcLocalDateTime), MariaDBConnections)
      row.get("ts", classOf[Instant]) shouldBe instant
      row.get(0, classOf[Instant]) shouldBe instant
      row.get("col", classOf[String]) shouldBe "text"
    }

    "read null timestamp as null Instant" in {
      val row = new ConnectionAdapter.AdaptedRow(new LocalDateTimeRow(null), MariaDBConnections)
      row.get("ts", classOf[Instant]) shouldBe null
    }

    "detect the MariaDB driver" in {
      def driver(name: String) =
        MariaDBConnections.isMariaDB(
          ConnectionFactoryOptions.builder().option(ConnectionFactoryOptions.DRIVER, name).build())
      def url(url: String) = MariaDBConnections.isMariaDB(ConnectionFactoryOptions.parse(url))
      driver("mariadb") shouldBe true
      driver("mysql") shouldBe false
      driver("postgresql") shouldBe false
      url("r2dbc:mariadb://localhost:3306/db") shouldBe true
      url("r2dbc:pool:mariadb://localhost:3306/db") shouldBe true
      url("r2dbc:mysql://localhost:3306/db") shouldBe false
    }
  }
}
