/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2022 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.state.javadsl

import java.util.Objects

import org.apache.pekko
import pekko.annotation.ApiMayChange
import pekko.annotation.InternalApi

@ApiMayChange
object AdditionalColumn {
  final class Upsert[A](
      val persistenceId: String,
      val entityType: String,
      val slice: Int,
      val revision: Long,
      val value: A) {

    override def equals(other: Any): Boolean = other match {
      case that: Upsert[?] =>
        persistenceId == that.persistenceId && entityType == that.entityType && slice == that.slice &&
        revision == that.revision && value == that.value
      case _ => false
    }

    override def hashCode: Int =
      Objects.hash(persistenceId, entityType, Int.box(slice), Long.box(revision), value.asInstanceOf[AnyRef])

    override def toString: String =
      s"Upsert($persistenceId,$entityType,$slice,$revision,$value)"
  }

  sealed trait Binding[+B]

  def bindValue[B](value: B): Binding[B] = new BindValue(value)

  def bindNull[B]: Binding[B] = BindNull

  def skip[B]: Binding[B] = Skip

  /**
   * INTERNAL API
   */
  @InternalApi private[pekko] final class BindValue[B](val value: B) extends Binding[B]

  /**
   * INTERNAL API
   */
  @InternalApi private[pekko] case object BindNull extends Binding[Nothing]

  /**
   * INTERNAL API
   */
  @InternalApi private[pekko] case object Skip extends Binding[Nothing]

}

/**
 * @tparam A
 *   The type of the durable state
 * @tparam B
 *   The type of the field stored in the additional column.
 */
@ApiMayChange
abstract class AdditionalColumn[A, B] {

  def fieldClass: Class[B]

  def columnName: String

  def bind(upsert: AdditionalColumn.Upsert[A]): AdditionalColumn.Binding[B]

}
