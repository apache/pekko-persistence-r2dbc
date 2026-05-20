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

package org.apache.pekko.persistence.r2dbc.session.javadsl

import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.function.{ Function => JFunction }

import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._
import scala.jdk.OptionConverters._
import scala.concurrent.ExecutionContext

import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.annotation.ApiMayChange
import pekko.persistence.r2dbc.internal.R2dbcExecutor
import pekko.persistence.r2dbc.session.{ scaladsl => scaladslSession }
import io.r2dbc.spi.Connection
import io.r2dbc.spi.Row
import io.r2dbc.spi.Statement

@ApiMayChange
object R2dbcSession {

  /**
   * Runs the passed function using a R2dbcSession with a new transaction. The connection is closed and the transaction
   * is committed at the end or rolled back in case of failures.
   */
  def withSession[A](system: ActorSystem[_], fun: JFunction[R2dbcSession, CompletionStage[A]]): CompletionStage[A] = {
    withSession(system, "pekko.persistence.r2dbc.connection-factory", fun)
  }

  def withSession[A](
      system: ActorSystem[_],
      connectionFactoryConfigPath: String,
      fun: JFunction[R2dbcSession, CompletionStage[A]]): CompletionStage[A] = {
    scaladslSession.R2dbcSession.withSession(system, connectionFactoryConfigPath) { scaladslSession =>
      val javadslSession = new R2dbcSession(scaladslSession.connection)(system.executionContext, system)
      fun(javadslSession).asScala
    }
  }.asJava

}

@ApiMayChange
final class R2dbcSession(val connection: Connection)(implicit ec: ExecutionContext, system: ActorSystem[_]) {

  def createStatement(sql: String): Statement =
    connection.createStatement(sql)

  def updateOne(statement: Statement): CompletionStage[java.lang.Long] =
    R2dbcExecutor.updateOneInTx(statement).map(java.lang.Long.valueOf)(ExecutionContext.parasitic).asJava

  def update(statements: java.util.List[Statement]): CompletionStage[java.util.List[java.lang.Long]] =
    R2dbcExecutor
      .updateInTx(statements.asScala.toVector)
      .map(results => results.map(java.lang.Long.valueOf).asJava)
      .asJava

  def selectOne[A](statement: Statement)(mapRow: Row => A): CompletionStage[Optional[A]] =
    R2dbcExecutor.selectOneInTx(statement, mapRow).map(_.toJava)(ExecutionContext.parasitic).asJava

  def select[A](statement: Statement)(mapRow: Row => A): CompletionStage[java.util.List[A]] =
    R2dbcExecutor.selectInTx(statement, mapRow).map(_.asJava).asJava

}
