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

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.function.BiFunction
import java.util.function.Predicate

import org.apache.pekko
import pekko.annotation.InternalApi
import io.r2dbc.spi.Batch
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.spi.ConnectionMetadata
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.Readable
import io.r2dbc.spi.Result
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import io.r2dbc.spi.Statement
import io.r2dbc.spi.TransactionDefinition
import io.r2dbc.spi.ValidationDepth
import io.r2dbc.spi.Wrapped
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * INTERNAL API
 *
 * Adapts an R2DBC driver to the types used by the plugin, for drivers that don't support all of them. Wraps the
 * connections of a `ConnectionFactory` and converts the values bound to statements and read from rows. Subclasses
 * override the conversions they need, the defaults pass the values through unchanged.
 */
@InternalApi
private[r2dbc] abstract class ConnectionAdapter {

  def bindValue(value: Any): Any = value

  def bindNullType(tpe: Class[?]): Class[?] = tpe

  def get[T](row: Row, index: Int, tpe: Class[T]): T = row.get(index, tpe)

  def get[T](row: Row, name: String, tpe: Class[T]): T = row.get(name, tpe)

  final def wrap(connectionFactory: ConnectionFactory): ConnectionFactory =
    new ConnectionAdapter.AdaptedConnectionFactory(connectionFactory, this)
}

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] object ConnectionAdapter {

  /**
   * For drivers without `java.time.Instant` support. Instants are bound and read as `LocalDateTime` in UTC, so the
   * database session must use the UTC time zone.
   */
  trait InstantAsUtcLocalDateTime extends ConnectionAdapter {

    override def bindValue(value: Any): Any =
      value match {
        case instant: Instant => LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
        case other            => super.bindValue(other)
      }

    override def bindNullType(tpe: Class[?]): Class[?] =
      if (tpe == classOf[Instant]) classOf[LocalDateTime]
      else super.bindNullType(tpe)

    override def get[T](row: Row, index: Int, tpe: Class[T]): T =
      if (tpe == classOf[Instant]) toInstant(super.get(row, index, classOf[LocalDateTime])).asInstanceOf[T]
      else super.get(row, index, tpe)

    override def get[T](row: Row, name: String, tpe: Class[T]): T =
      if (tpe == classOf[Instant]) toInstant(super.get(row, name, classOf[LocalDateTime])).asInstanceOf[T]
      else super.get(row, name, tpe)

    private def toInstant(localDateTime: LocalDateTime): Instant =
      if (localDateTime == null) null else localDateTime.toInstant(ZoneOffset.UTC)
  }

  // Wrapped, like the connection pool, so that the wrapped connection factory can be disposed
  private[internal] final class AdaptedConnectionFactory(underlying: ConnectionFactory, adapter: ConnectionAdapter)
      extends ConnectionFactory
      with Wrapped[ConnectionFactory] {
    override def unwrap(): ConnectionFactory = underlying
    override def create(): Publisher[? <: Connection] =
      Mono.from[Connection](underlying.create()).map[Connection]((connection: Connection) =>
        new AdaptedConnection(connection, adapter))

    override def getMetadata: ConnectionFactoryMetadata = underlying.getMetadata
  }

  private[internal] final class AdaptedConnection(underlying: Connection, adapter: ConnectionAdapter)
      extends Connection
      with Wrapped[Connection] {
    // The underlying connection is the pooled connection, which unwraps to the driver connection. Unwrapping to the
    // driver connection directly behaves the same as the pooled connections of the other databases.
    override def unwrap(): Connection =
      underlying match {
        case wrapped: Wrapped[Connection] @unchecked => wrapped.unwrap()
        case connection                              => connection
      }
    override def beginTransaction(): Publisher[Void] = underlying.beginTransaction()
    override def beginTransaction(definition: TransactionDefinition): Publisher[Void] =
      underlying.beginTransaction(definition)
    override def close(): Publisher[Void] = underlying.close()
    override def commitTransaction(): Publisher[Void] = underlying.commitTransaction()
    override def createBatch(): Batch = underlying.createBatch()
    override def createSavepoint(name: String): Publisher[Void] = underlying.createSavepoint(name)
    override def createStatement(sql: String): Statement =
      new AdaptedStatement(underlying.createStatement(sql), adapter)
    override def isAutoCommit: Boolean = underlying.isAutoCommit
    override def getMetadata: ConnectionMetadata = underlying.getMetadata
    override def getTransactionIsolationLevel: IsolationLevel = underlying.getTransactionIsolationLevel
    override def releaseSavepoint(name: String): Publisher[Void] = underlying.releaseSavepoint(name)
    override def rollbackTransaction(): Publisher[Void] = underlying.rollbackTransaction()
    override def rollbackTransactionToSavepoint(name: String): Publisher[Void] =
      underlying.rollbackTransactionToSavepoint(name)
    override def setAutoCommit(autoCommit: Boolean): Publisher[Void] = underlying.setAutoCommit(autoCommit)
    override def setLockWaitTimeout(timeout: Duration): Publisher[Void] = underlying.setLockWaitTimeout(timeout)
    override def setStatementTimeout(timeout: Duration): Publisher[Void] = underlying.setStatementTimeout(timeout)
    override def setTransactionIsolationLevel(isolationLevel: IsolationLevel): Publisher[Void] =
      underlying.setTransactionIsolationLevel(isolationLevel)
    override def validate(depth: ValidationDepth): Publisher[java.lang.Boolean] = underlying.validate(depth)
  }

  private[internal] final class AdaptedStatement(underlying: Statement, adapter: ConnectionAdapter)
      extends Statement {
    override def add(): Statement = {
      underlying.add()
      this
    }
    override def bind(index: Int, value: Any): Statement = {
      underlying.bind(index, adapter.bindValue(value))
      this
    }
    override def bind(name: String, value: Any): Statement = {
      underlying.bind(name, adapter.bindValue(value))
      this
    }
    override def bindNull(index: Int, tpe: Class[?]): Statement = {
      underlying.bindNull(index, adapter.bindNullType(tpe))
      this
    }
    override def bindNull(name: String, tpe: Class[?]): Statement = {
      underlying.bindNull(name, adapter.bindNullType(tpe))
      this
    }
    override def execute(): Publisher[? <: Result] =
      Flux.from[Result](underlying.execute()).map[Result]((result: Result) => new AdaptedResult(result, adapter))
    override def returnGeneratedValues(columns: String*): Statement = {
      underlying.returnGeneratedValues(columns: _*)
      this
    }
    override def fetchSize(rows: Int): Statement = {
      underlying.fetchSize(rows)
      this
    }
  }

  private final class AdaptedResult(underlying: Result, adapter: ConnectionAdapter) extends Result {
    override def getRowsUpdated: Publisher[java.lang.Long] = underlying.getRowsUpdated

    override def map[T](mappingFunction: BiFunction[Row, RowMetadata, ? <: T]): Publisher[T] =
      underlying.map[T](new BiFunction[Row, RowMetadata, T] {
        override def apply(row: Row, metadata: RowMetadata): T =
          mappingFunction.apply(new AdaptedRow(row, adapter), metadata)
      })

    override def map[T](mappingFunction: java.util.function.Function[? >: Readable, ? <: T]): Publisher[T] =
      underlying.map[T](new java.util.function.Function[Readable, T] {
        override def apply(readable: Readable): T =
          readable match {
            case row: Row => mappingFunction.apply(new AdaptedRow(row, adapter))
            case other    => mappingFunction.apply(other)
          }
      })

    override def filter(filter: Predicate[Result.Segment]): Result =
      new AdaptedResult(underlying.filter(filter), adapter)

    override def flatMap[T](
        mappingFunction: java.util.function.Function[Result.Segment, ? <: Publisher[? <: T]]): Publisher[T] =
      underlying.flatMap[T]((segment: Result.Segment) =>
        segment match {
          case rowSegment: Result.RowSegment =>
            val adaptedRow: Row = new AdaptedRow(rowSegment.row(), adapter)
            mappingFunction.apply(new Result.RowSegment {
              override def row(): Row = adaptedRow
            })
          case other => mappingFunction.apply(other)
        })
  }

  private[internal] final class AdaptedRow(underlying: Row, adapter: ConnectionAdapter) extends Row {
    override def getMetadata: RowMetadata = underlying.getMetadata
    override def get[T](index: Int, tpe: Class[T]): T = adapter.get(underlying, index, tpe)
    override def get[T](name: String, tpe: Class[T]): T = adapter.get(underlying, name, tpe)
  }
}
