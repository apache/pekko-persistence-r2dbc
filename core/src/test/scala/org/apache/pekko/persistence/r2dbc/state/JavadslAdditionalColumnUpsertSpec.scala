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

package org.apache.pekko.persistence.r2dbc.state

import org.apache.pekko
import pekko.persistence.r2dbc.state.javadsl.AdditionalColumn
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class JavadslAdditionalColumnUpsertSpec extends AnyWordSpec with Matchers {

  private def upsert(value: String) = new AdditionalColumn.Upsert[String]("pid", "entity", 3, 7L, value)

  "javadsl.AdditionalColumn.Upsert" should {

    "not expose Scala case class types to Java" in {
      classOf[Product].isAssignableFrom(classOf[AdditionalColumn.Upsert[?]]) shouldBe false
    }

    "provide accessors" in {
      val u = upsert("a")
      u.persistenceId shouldBe "pid"
      u.entityType shouldBe "entity"
      u.slice shouldBe 3
      u.revision shouldBe 7L
      u.value shouldBe "a"
    }

    "keep value equality and string representation" in {
      upsert("a") shouldBe upsert("a")
      upsert("a").hashCode shouldBe upsert("a").hashCode
      upsert("a") should not be upsert("b")
      upsert(null) shouldBe upsert(null)
      upsert("a").toString shouldBe "Upsert(pid,entity,3,7,a)"
    }
  }
}
