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
import pekko.actor.typed.ActorSystem
import pekko.persistence.r2dbc.TestConfig
import pekko.persistence.r2dbc.TestDbLifecycle
import org.scalatest.wordspec.AnyWordSpecLike

class JavadslR2dbcSessionSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with LogCapturing {

  override def typedSystem: ActorSystem[?] = system

  "javadsl.R2dbcSession" should {

    "accept a java.util.function.Function in selectOne" in {
      JavadslSessionSelect.selectOne(system).toCompletableFuture.get() shouldBe Optional.of("a")
    }

    "accept a java.util.function.Function in select" in {
      JavadslSessionSelect.select(system).toCompletableFuture.get().asScala.toList shouldBe List("a")
    }

    "accept a row mapper taking a supertype of Row in selectOne" in {
      JavadslSessionSelect.selectOneWithSuperTypeArgument(system).toCompletableFuture.get() shouldBe Optional.of("a")
    }

    "accept a row mapper taking a supertype of Row in select" in {
      JavadslSessionSelect.selectWithSuperTypeArgument(system).toCompletableFuture.get().asScala.toList shouldBe
      List("a")
    }

    "accept a row mapper returning a subtype of the result type in selectOne" in {
      JavadslSessionSelect.selectOneWithSubTypeResult(system).toCompletableFuture.get() shouldBe Optional.of("a")
    }

    "accept a row mapper returning a subtype of the result type in select" in {
      JavadslSessionSelect.selectWithSubTypeResult(system).toCompletableFuture.get().asScala.toList shouldBe List("a")
    }

    "accept a list of a subtype of Statement in update" in {
      JavadslSessionSelect.updateWithWildcardList(system).toCompletableFuture.get().asScala.toList shouldBe
      List(java.lang.Long.valueOf(0L))
    }

    "be possible to create from Java without an ExecutionContext" in {
      JavadslSessionSelect.selectWithPublicConstructor(system).toCompletableFuture.get().asScala.toList shouldBe
      List("a")
    }
  }
}
