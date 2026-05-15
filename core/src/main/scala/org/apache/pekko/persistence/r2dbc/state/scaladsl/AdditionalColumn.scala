/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.state.scaladsl

import scala.reflect.ClassTag

import org.apache.pekko
import pekko.annotation.ApiMayChange
import pekko.annotation.InternalApi

@ApiMayChange
object AdditionalColumn {
  final case class Upsert[A](persistenceId: String, entityType: String, slice: Int, revision: Long, value: A)

  sealed trait Binding[+B]

  final case class BindValue[B](value: B) extends Binding[B]

  case object BindNull extends Binding[Nothing]

  case object Skip extends Binding[Nothing]

  private val scalaPrimitivesMapping: Map[Class[_], Class[_]] =
    Map(
      classOf[Int] -> classOf[java.lang.Integer],
      classOf[Long] -> classOf[java.lang.Long],
      classOf[Float] -> classOf[java.lang.Float],
      classOf[Double] -> classOf[java.lang.Double],
      classOf[Byte] -> classOf[java.lang.Byte],
      classOf[Short] -> classOf[java.lang.Short],
      classOf[Char] -> classOf[java.lang.Character])
}

/**
 * @tparam A
 *   The type of the durable state
 * @tparam B
 *   The type of the field stored in the additional column.
 */
@ApiMayChange
abstract class AdditionalColumn[A, B: ClassTag] {
  import AdditionalColumn.scalaPrimitivesMapping

  /**
   * INTERNAL API: used when binding null
   */
  @InternalApi private[pekko] val fieldClass: Class[_] = {
    val cls = implicitly[ClassTag[B]].runtimeClass
    scalaPrimitivesMapping.getOrElse(cls, cls)
  }

  def columnName: String

  def bind(upsert: AdditionalColumn.Upsert[A]): AdditionalColumn.Binding[B]

}
