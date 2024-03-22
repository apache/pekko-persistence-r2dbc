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

package org.apache.pekko.persistence.r2dbc.internal

import org.apache.pekko.annotation.InternalApi
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.Operators

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ Future, Promise }

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] final class MonoToFuture[T] extends AtomicReference[Subscription] with CoreSubscriber[T] {
  private final val promise = Promise[T]()

  override def onSubscribe(s: Subscription): Unit = {
    if (Operators.validate(getAndSet(s), s)) {
      s.request(1) // we just need 1 value.
    } else {
      s.cancel()
    }
  }

  override def onNext(t: T): Unit = {
    val currentSubscription = getAndSet(null)
    if (currentSubscription ne null) {
      promise.success(t)
      // NOTE: We should not call cancel here when subscribe to a Mono
      // https://github.com/reactor/reactor-core/issues/2070
    } else Operators.onNextDropped(t, currentContext())
  }

  override def onError(t: Throwable): Unit = {
    if (getAndSet(null) ne null) {
      promise.failure(t)
    } else Operators.onErrorDropped(t, currentContext())
  }

  override def onComplete(): Unit = {
    if (getAndSet(null) ne null) {
      promise.success(null.asInstanceOf[T])
    }
  }

  def future: Future[T] = promise.future
}
