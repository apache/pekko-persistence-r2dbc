/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc

import com.typesafe.config._
import io.r2dbc.spi.ConnectionFactoryOptions
import org.apache.pekko.actor.testkit.typed.scaladsl._
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.eventstream.EventStream
import org.apache.pekko.persistence.r2dbc.OptionsCustomizerSpec._
import org.scalatest.wordspec.AnyWordSpecLike

class OptionsCustomizerSpec extends ScalaTestWithActorTestKit(config) with AnyWordSpecLike {
  "ConnectionFactoryProvider" should {
    "instantiate and apply a custom OptionsCustomizer when options-customizer settings is set" in {
      val probe = TestProbe[CustomizerCalled.type]()
      system.eventStream.tell(EventStream.Subscribe(probe.ref))

      ConnectionFactoryProvider(system).connectionFactoryFor("pekko.persistence.r2dbc.connection-factory")
      probe.expectMessage(CustomizerCalled)
    }
  }
}

object OptionsCustomizerSpec {
  object CustomizerCalled

  class Customizer(system: ActorSystem[_]) extends ConnectionFactoryProvider.OptionsCustomizer {
    override def apply(options: ConnectionFactoryOptions, config: Config): ConnectionFactoryOptions = {
      system.eventStream.tell(EventStream.Publish(CustomizerCalled))
      options
    }
  }

  val config: Config = ConfigFactory.parseString("""
    pekko.persistence.r2dbc.connection-factory {
      options-customizer = "org.apache.pekko.persistence.r2dbc.OptionsCustomizerSpec$Customizer"
    }
    """).withFallback(TestConfig.config)
}
