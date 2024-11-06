/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

import com.typesafe.config._
import io.r2dbc.spi.ConnectionFactoryOptions
import org.apache.pekko.actor.testkit.typed.scaladsl._
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.eventstream.EventStream
import org.apache.pekko.persistence.r2dbc.ConnectionFactoryOptionsCustomizerSpec._
import org.scalatest.wordspec.AnyWordSpecLike

class ConnectionFactoryOptionsCustomizerSpec extends ScalaTestWithActorTestKit(config) with AnyWordSpecLike {
  "ConnectionFactoryProvider" should {
    "instantiate and apply a custom ConnectionFactoryOptionsCustomizer when connection-factory-options-customizer settings is set" in {
      val probe = TestProbe[CustomizerCalled.type]()
      system.eventStream.tell(EventStream.Subscribe(probe.ref))

      ConnectionFactoryProvider(system).connectionFactoryFor("pekko.persistence.r2dbc.connection-factory")
      probe.expectMessage(CustomizerCalled)
    }
  }
}

object ConnectionFactoryOptionsCustomizerSpec {
  object CustomizerCalled

  class Customizer(system: ActorSystem[_]) extends ConnectionFactoryProvider.ConnectionFactoryOptionsCustomizer {
    override def apply(options: ConnectionFactoryOptions, config: Config): ConnectionFactoryOptions = {
      system.eventStream.tell(EventStream.Publish(CustomizerCalled))
      options
    }
  }

  val config: Config = ConfigFactory.parseString("""
    pekko.persistence.r2dbc.connection-factory {
      connection-factory-options-customizer = "org.apache.pekko.persistence.r2dbc.ConnectionFactoryOptionsCustomizerSpec$Customizer"
    }
    """).withFallback(TestConfig.config)
}
