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
import io.r2dbc.spi.Row

/**
 * INTERNAL API
 *
 * Reads the columns of the plugin tables that can hold empty strings or byte arrays. Oracle stores empty values as
 * `NULL`, so a `NULL` in these columns is read back as an empty value. The other databases have `NOT NULL` constraints
 * on these columns, or read them only when they have a value.
 */
@InternalApi
private[r2dbc] object EmptyValues {

  def string(row: Row, name: String): String =
    row.get(name, classOf[String]) match {
      case null  => ""
      case value => value
    }

  def bytes(row: Row, name: String): Array[Byte] =
    row.get(name, classOf[Array[Byte]]) match {
      case null  => Array.emptyByteArray
      case value => value
    }
}
