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

package org.apache.pekko.persistence.r2dbc.session

import java.util.Optional

import scala.jdk.CollectionConverters._

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.persistence.r2dbc.TestConfig
import org.scalatest.wordspec.AnyWordSpecLike

class JavadslR2dbcSessionSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with LogCapturing {

  "javadsl.R2dbcSession" should {

    "accept a java.util.function.Function in selectOne" in {
      JavadslSessionSelect.selectOne(system).toCompletableFuture.get() shouldBe Optional.of("a")
    }

    "accept a java.util.function.Function in select" in {
      JavadslSessionSelect.select(system).toCompletableFuture.get().asScala.toList shouldBe List("a")
    }
  }
}
