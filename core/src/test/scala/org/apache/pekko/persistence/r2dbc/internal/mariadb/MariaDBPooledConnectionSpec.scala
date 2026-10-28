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

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.persistence.r2dbc.ConnectionFactoryProvider
import pekko.persistence.r2dbc.Dialect
import pekko.persistence.r2dbc.TestConfig
import io.r2dbc.spi.Connection
import io.r2dbc.spi.Wrapped
import org.mariadb.r2dbc.MariadbConnection
import org.scalatest.Outcome
import org.scalatest.Pending
import org.scalatest.wordspec.AnyWordSpecLike
import reactor.core.publisher.Mono

class MariaDBPooledConnectionSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with LogCapturing {

  override def withFixture(test: NoArgTest): Outcome =
    if (TestConfig.dialect == Dialect.MariaDB) super.withFixture(test)
    else Pending // only for the MariaDB driver

  "The connections of the MariaDB connection factory" should {
    "unwrap to the driver connection, like the pooled connections of the other databases" in {
      val connectionFactory =
        ConnectionFactoryProvider(system).connectionFactoryFor("pekko.persistence.r2dbc.connection-factory")
      val connection = Mono.from(connectionFactory.create()).block()
      try {
        connection.asInstanceOf[Wrapped[Connection]].unwrap() shouldBe a[MariadbConnection]
      } finally {
        Mono.from(connection.close()).block()
      }
    }
  }
}
