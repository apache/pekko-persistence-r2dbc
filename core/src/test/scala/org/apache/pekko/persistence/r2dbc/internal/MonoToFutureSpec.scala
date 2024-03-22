/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.internal

import org.scalatest.TestSuite
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import reactor.core.publisher.Mono

import java.util.concurrent.atomic.AtomicBoolean

class MonoToFutureSpec extends AnyWordSpec with ScalaFutures with TestSuite with Matchers {
  "MonoToFutureSpec" should {
    "convert a Mono to a Future in happy path" in {
      val r = Mono.just("pekko")
        .subscribeWith(new MonoToFuture[String]())
        .future
      r.futureValue shouldBe "pekko"
    }

    "convert a failed Mono to a failed Future" in {
      val r = Mono.error(new RuntimeException("pekko"))
        .subscribeWith(new MonoToFuture[String]())
        .future
      r.failed.futureValue.getMessage shouldBe "pekko"
    }

    "convert an empty Mono to a Future with null" in {
      val r = Mono.empty[String]
        .subscribeWith(new MonoToFuture[String]())
        .future
      r.futureValue shouldBe null
    }

    "will not cancel the origin Mono after Future completes" in {
      val canceledFlag = new AtomicBoolean(false)
      val r = Mono.just("pekko")
        .doOnCancel(() => canceledFlag.set(true))
        .subscribeWith(new MonoToFuture[String]())
        .future
      r.futureValue shouldBe "pekko"
      canceledFlag.get() shouldBe false
    }

  }
}
