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

import scala.concurrent.duration._

import org.apache.pekko
import pekko.actor.Props
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.persistence.CapabilityFlag
import pekko.persistence.journal.JournalPerfSpec
import pekko.persistence.journal.JournalPerfSpec.BenchActor
import pekko.persistence.journal.JournalPerfSpec.Cmd
import pekko.persistence.journal.JournalPerfSpec.ResetCounter
import pekko.persistence.r2dbc.TestDbLifecycle
import pekko.testkit.TestProbe

class R2dbcBatchJournalPerfManyActorsSpec extends JournalPerfSpec(R2dbcBatchJournalPerfSpec.config)
    with TestDbLifecycle
    with BatchedJournalTckDialectGate {
  override def eventsCount: Int = 10

  override def measurementIterations: Int = 2 // increase when testing for real

  override def awaitDurationMillis: Long = 60.seconds.toMillis

  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.off()

  override def typedSystem: ActorSystem[?] = system.toTyped

  def actorCount = 20 // increase when testing for real

  private val commands = Vector(1 to eventsCount: _*)

  "A PersistentActor's performance with journal batching" must {
    s"measure: persist()-ing $eventsCount events for $actorCount actors" in {
      val testProbe = TestProbe()
      val replyAfter = eventsCount
      def createBenchActor(actorNumber: Int) =
        system.actorOf(Props(classOf[BenchActor], s"$pid-$actorNumber", testProbe.ref, replyAfter))
      val actors = 1.to(actorCount).map(createBenchActor)

      measure(d => s"Persist()-ing $eventsCount * $actorCount took ${d.toMillis} ms") {
        for (cmd <- commands; actor <- actors) {
          actor ! Cmd("p", cmd)
        }
        for (_ <- actors) {
          testProbe.expectMsg(awaitDurationMillis.millis, commands.last)
        }
        for (actor <- actors) {
          actor ! ResetCounter
        }
      }
    }
  }
}
