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

import java.time.{ Duration => JDuration }
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiFunction
import java.util.function.Predicate

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorSystem
import io.r2dbc.spi.Batch
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.spi.ConnectionMetadata
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.R2dbcNonTransientResourceException
import io.r2dbc.spi.Result
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import io.r2dbc.spi.Statement
import io.r2dbc.spi.TransactionDefinition
import io.r2dbc.spi.ValidationDepth
import org.reactivestreams.Publisher
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

object R2dbcExecutorConnectionReleaseSpec {

  private def notImplemented(): Nothing =
    throw new UnsupportedOperationException("not used by these tests")

  /**
   * Connection that can be told to fail the call that `R2dbcExecutor` makes before handing the connection to the
   * caller, and to mimic the `IllegalStateException` that `io.r2dbc.pool.PooledConnection` throws from
   * `assertNotClosed` when the connection has already been closed.
   */
  final class FakeConnection(
      beginTransactionFailure: Option[Throwable] = None,
      setAutoCommitFailure: Option[Throwable] = None,
      closeThrows: Boolean = false,
      rows: immutable.IndexedSeq[String] = Vector.empty)
      extends Connection {

    private val closeAttempts = new AtomicInteger

    def closeCount: Int = closeAttempts.get

    private def emptyOrFailed(failure: Option[Throwable]): Publisher[Void] =
      failure match {
        case Some(exc) => Mono.error[Void](exc)
        case None      => Mono.empty[Void]()
      }

    override def close(): Publisher[Void] = {
      closeAttempts.incrementAndGet()
      if (closeThrows) throw new IllegalStateException("Connection is closed")
      else Mono.empty[Void]()
    }

    override def beginTransaction(): Publisher[Void] = emptyOrFailed(beginTransactionFailure)

    override def setAutoCommit(autoCommit: Boolean): Publisher[Void] = emptyOrFailed(setAutoCommitFailure)

    override def commitTransaction(): Publisher[Void] = Mono.empty[Void]()

    override def rollbackTransaction(): Publisher[Void] = Mono.empty[Void]()

    override def createStatement(sql: String): Statement = new FakeStatement(rows)

    override def beginTransaction(definition: TransactionDefinition): Publisher[Void] = notImplemented()
    override def createBatch(): Batch = notImplemented()
    override def createSavepoint(name: String): Publisher[Void] = notImplemented()
    override def isAutoCommit: Boolean = notImplemented()
    override def getMetadata: ConnectionMetadata = notImplemented()
    override def getTransactionIsolationLevel: IsolationLevel = notImplemented()
    override def releaseSavepoint(name: String): Publisher[Void] = notImplemented()
    override def rollbackTransactionToSavepoint(name: String): Publisher[Void] = notImplemented()
    override def setLockWaitTimeout(timeout: JDuration): Publisher[Void] = notImplemented()
    override def setStatementTimeout(timeout: JDuration): Publisher[Void] = notImplemented()
    override def setTransactionIsolationLevel(isolationLevel: IsolationLevel): Publisher[Void] = notImplemented()
    override def validate(depth: ValidationDepth): Publisher[java.lang.Boolean] = notImplemented()
  }

  final class FakeStatement(rows: immutable.IndexedSeq[String]) extends Statement {
    override def execute(): Publisher[? <: Result] = Mono.just(new FakeResult(rows))

    override def add(): Statement = notImplemented()
    override def bind(index: Int, value: Any): Statement = notImplemented()
    override def bind(name: String, value: Any): Statement = notImplemented()
    override def bindNull(index: Int, `type`: Class[?]): Statement = notImplemented()
    override def bindNull(name: String, `type`: Class[?]): Statement = notImplemented()
  }

  final class FakeResult(rows: immutable.IndexedSeq[String]) extends Result {
    override def map[T](mappingFunction: BiFunction[Row, RowMetadata, ? <: T]): Publisher[T] =
      Flux.fromIterable(rows.map(row => mappingFunction.apply(new FakeRow(row), null): T).asJava)

    override def getRowsUpdated: Publisher[java.lang.Long] = Mono.just(java.lang.Long.valueOf(rows.size.toLong))

    override def filter(filter: Predicate[Result.Segment]): Result = notImplemented()
    override def flatMap[T](
        mappingFunction: java.util.function.Function[Result.Segment, ? <: Publisher[? <: T]]): Publisher[T] =
      notImplemented()
  }

  final class FakeRow(value: String) extends Row {
    override def get[T](name: String, `type`: Class[T]): T = `type`.cast(value)

    override def get[T](index: Int, `type`: Class[T]): T = `type`.cast(value)
    override def getMetadata: RowMetadata = notImplemented()
  }

  final class FakeConnectionFactory(connection: Connection) extends ConnectionFactory {
    override def create(): Publisher[? <: Connection] = Mono.just(connection)

    override def getMetadata: ConnectionFactoryMetadata = new ConnectionFactoryMetadata {
      override def getName: String = "fake"
    }
  }
}

class R2dbcExecutorConnectionReleaseSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {
  import R2dbcExecutorConnectionReleaseSpec._

  private val log = LoggerFactory.getLogger(classOf[R2dbcExecutorConnectionReleaseSpec])

  private implicit val ec: ExecutionContext = testKit.system.executionContext
  private implicit val typedSystem: ActorSystem[?] = testKit.system

  // no close-calls-exceeding watchdog, so a connection that is not released by the executor itself
  // stays leaked for the duration of the test
  private def executorFor(connection: Connection): R2dbcExecutor =
    new R2dbcExecutor(new FakeConnectionFactory(connection), log, logDbCallsExceeding = -1.millis,
      closeCallsExceeding = None)

  "R2dbcExecutor" should {

    "close the connection when beginTransaction fails" in {
      val failure = new R2dbcNonTransientResourceException("connection closed by the server")
      val connection = new FakeConnection(beginTransactionFailure = Some(failure))

      executorFor(connection)
        .withConnection("test") { _ => fail("the function should not be called when beginTransaction fails") }
        .failed
        .futureValue shouldBe failure

      eventually {
        connection.closeCount shouldBe 1
      }
    }

    "close the connection when setting auto-commit fails" in {
      val failure = new R2dbcNonTransientResourceException("connection closed by the server")
      val connection = new FakeConnection(setAutoCommitFailure = Some(failure))

      executorFor(connection)
        .withAutoCommitConnection("test") { _ => fail("the function should not be called when setAutoCommit fails") }
        .failed
        .futureValue shouldBe failure

      eventually {
        connection.closeCount shouldBe 1
      }
    }

    "return the selected rows when the connection was already closed by the timeout task" in {
      val connection = new FakeConnection(closeThrows = true, rows = Vector("a", "b"))

      executorFor(connection)
        .select("test")(_.createStatement("select col from tbl"), row => row.get("col", classOf[String]))
        .futureValue shouldBe Vector("a", "b")

      connection.closeCount shouldBe 1
    }
  }
}
