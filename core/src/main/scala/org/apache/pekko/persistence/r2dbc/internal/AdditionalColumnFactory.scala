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

package org.apache.pekko.persistence.r2dbc.internal

import scala.util.Try

import org.apache.pekko
import pekko.actor.{ ActorSystem => ClassicActorSystem }
import pekko.actor.ExtendedActorSystem
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.state.scaladsl.AdditionalColumn
import pekko.persistence.r2dbc.state.{ javadsl => javadslState }

/**
 * INTERNAL API
 */
@InternalApi private[pekko] object AdditionalColumnFactory {

  /**
   * Adapter from javadsl.AdditionalColumn to scaladsl.AdditionalColumn
   */
  final class AdditionalColumnAdapter(delegate: javadslState.AdditionalColumn[Any, Any])
      extends AdditionalColumn[Any, Any] {

    override private[pekko] val fieldClass: Class[_] =
      delegate.fieldClass

    override def columnName: String =
      delegate.columnName

    override def bind(upsert: AdditionalColumn.Upsert[Any]): AdditionalColumn.Binding[Any] = {
      val javadslUpsert = new javadslState.AdditionalColumn.Upsert[Any](
        upsert.persistenceId,
        upsert.entityType,
        upsert.slice,
        upsert.revision,
        upsert.value)
      delegate.bind(javadslUpsert) match {
        case bindValue: javadslState.AdditionalColumn.BindValue[_] => AdditionalColumn.BindValue(bindValue.value)
        case javadslState.AdditionalColumn.BindNull                => AdditionalColumn.BindNull
        case javadslState.AdditionalColumn.Skip                    => AdditionalColumn.Skip
      }
    }

  }

  def create(system: ActorSystem[_], fqcn: String): AdditionalColumn[Any, Any] = {
    val dynamicAccess = system.classicSystem.asInstanceOf[ExtendedActorSystem].dynamicAccess

    def tryCreateScaladslInstance(): Try[AdditionalColumn[Any, Any]] = {
      dynamicAccess
        .createInstanceFor[AdditionalColumn[Any, Any]](fqcn, Nil)
        .orElse(
          dynamicAccess
            .createInstanceFor[AdditionalColumn[Any, Any]](fqcn, List(classOf[ActorSystem[_]] -> system))
            .orElse(dynamicAccess.createInstanceFor[AdditionalColumn[Any, Any]](
              fqcn,
              List(classOf[ClassicActorSystem] -> system.classicSystem))))
    }

    def tryCreateJavadslInstance(): Try[javadslState.AdditionalColumn[Any, Any]] = {
      dynamicAccess
        .createInstanceFor[javadslState.AdditionalColumn[Any, Any]](fqcn, Nil)
        .orElse(
          dynamicAccess
            .createInstanceFor[javadslState.AdditionalColumn[Any, Any]](
              fqcn,
              List(classOf[ActorSystem[_]] -> system))
            .orElse(dynamicAccess.createInstanceFor[javadslState.AdditionalColumn[Any, Any]](
              fqcn,
              List(classOf[ClassicActorSystem] -> system.classicSystem))))
    }

    def adapt(javadslColumn: javadslState.AdditionalColumn[Any, Any]): AdditionalColumn[Any, Any] =
      new AdditionalColumnAdapter(javadslColumn)

    tryCreateScaladslInstance()
      .orElse(tryCreateJavadslInstance().map(adapt))
      .getOrElse(
        throw new IllegalArgumentException(
          s"Additional column [$fqcn] must implement " +
          s"[${classOf[AdditionalColumn[_, _]].getName}] or [${classOf[javadslState.AdditionalColumn[_, _]].getName}]. It " +
          s"may have an ActorSystem constructor parameter."))
  }

}
