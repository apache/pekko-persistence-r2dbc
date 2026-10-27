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

package org.apache.pekko.persistence.r2dbc.internal.oracle

import java.nio.ByteBuffer
import java.util.Locale

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.internal.ConnectionAdapter
import io.r2dbc.spi.Blob
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Row
import reactor.core.publisher.Mono

/**
 * INTERNAL API
 *
 * Adapts the Oracle R2DBC driver to the types used by the plugin:
 *
 *   - `java.time.Instant` is not supported by the driver, so it is bound and read as a UTC `LocalDateTime`. The
 *     `TIMESTAMP` columns of the Oracle DDL have no time zone, so the session time zone doesn't affect them.
 *   - `Array[Byte]` is bound as a `Blob`, because the driver binds byte arrays as size limited `RAW` values in the
 *     `SELECT` of a `MERGE`.
 *   - Oracle stores empty strings and empty byte arrays as `NULL`, so the entity type, serializer manifest, writer and
 *     payload columns of the plugin tables are read back as empty values instead of `null`.
 */
@InternalApi
private[r2dbc] object OracleConnections extends ConnectionAdapter.InstantAsUtcLocalDateTime {

  def isOracle(options: ConnectionFactoryOptions): Boolean = {
    def is(option: io.r2dbc.spi.Option[?]): Boolean =
      Option(options.getValue(option)).exists(_.toString.toLowerCase(Locale.ROOT).startsWith("oracle"))
    is(ConnectionFactoryOptions.DRIVER) || is(ConnectionFactoryOptions.PROTOCOL)
  }

  private val EmptyStringColumns =
    Set("entity_type", "event_ser_manifest", "ser_manifest", "state_ser_manifest", "meta_ser_manifest", "writer")

  private val EmptyPayloadColumns = Set("event_payload", "snapshot", "state_payload")

  override def bindValue(value: Any): Any =
    value match {
      case bytes: Array[Byte] => Blob.from(Mono.just(ByteBuffer.wrap(bytes)))
      case other              => super.bindValue(other)
    }

  override def bindNullType(tpe: Class[?]): Class[?] =
    if (tpe == classOf[Array[Byte]]) classOf[Blob]
    else super.bindNullType(tpe)

  override def get[T](row: Row, name: String, tpe: Class[T]): T = {
    val value = super.get(row, name, tpe)
    if (value != null) value
    else {
      val column = name.toLowerCase(Locale.ROOT)
      if (tpe == classOf[String] && EmptyStringColumns(column)) "".asInstanceOf[T]
      else if (tpe == classOf[Array[Byte]] && EmptyPayloadColumns(column)) Array.emptyByteArray.asInstanceOf[T]
      else value
    }
  }
}
