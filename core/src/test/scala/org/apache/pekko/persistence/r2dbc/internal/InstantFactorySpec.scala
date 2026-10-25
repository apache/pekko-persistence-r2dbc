/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.internal

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters._

import org.scalatest.TestSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class InstantFactorySpec extends AnyWordSpec with TestSuite with Matchers {

  "InstantFactory.nowStrictlyIncreasing" should {

    "return strictly increasing instants when the clock doesn't advance between calls" in {
      // many more calls than the clock has distinct microseconds during the loop
      val instants = Vector.fill(100000)(InstantFactory.nowStrictlyIncreasing())
      instants.sliding(2).foreach { case Seq(a, b) => a should be < b }
    }

    "return distinct instants across threads" in {
      val results = new ConcurrentLinkedQueue[Instant]
      val threads =
        Vector.fill(4)(new Thread(() => (1 to 10000).foreach(_ => results.add(InstantFactory.nowStrictlyIncreasing()))))
      threads.foreach(_.start())
      threads.foreach(_.join())
      val all = results.asScala.toVector
      all.distinct.size shouldBe all.size
    }

    "return microsecond instants that are not behind now" in {
      val before = InstantFactory.now()
      val instant = InstantFactory.nowStrictlyIncreasing()
      instant should be >= before
      instant shouldBe instant.truncatedTo(ChronoUnit.MICROS)
    }
  }
}
