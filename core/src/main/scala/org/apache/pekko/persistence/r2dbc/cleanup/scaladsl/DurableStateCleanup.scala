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

package org.apache.pekko.persistence.r2dbc.cleanup.scaladsl

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import org.apache.pekko
import pekko.Done
import pekko.actor.ClassicActorSystemProvider
import pekko.actor.typed.ActorSystem
import pekko.annotation.ApiMayChange
import pekko.annotation.InternalApi
import pekko.persistence.r2dbc.CleanupSettings
import pekko.persistence.r2dbc.StateSettings
import pekko.persistence.r2dbc.state.scaladsl.DurableStateDao
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

/**
 * Scala API: Tool for deleting durable state for a given list of `persistenceIds` without using `DurableStateBehavior`
 * actors. It's important that the actors with corresponding persistenceId are not running at the same time as using the
 * tool.
 *
 * If `resetRevisionNumber` is `true` then the creating entity with the same `persistenceId` will start from 0.
 * Otherwise it will continue from the latest highest used revision number.
 *
 * WARNING: reusing the same `persistenceId` after resetting the revision number should be avoided, since it might be
 * confusing to reuse the same revision numbers for new state changes.
 *
 * When a list of `persistenceIds` are given they are deleted sequentially in the order of the list. It's possible to
 * parallelize the deletes by running several cleanup operations at the same time operating on different sets of
 * `persistenceIds`.
 * 
 * @since 2.0.0
 */
@ApiMayChange
final class DurableStateCleanup(systemProvider: ClassicActorSystemProvider, configPath: String) {

  def this(systemProvider: ClassicActorSystemProvider) =
    this(systemProvider, "pekko.persistence.r2dbc.cleanup")

  /**
   * INTERNAL API
   */
  @InternalApi private[pekko] implicit val system: ActorSystem[_] = {
    import pekko.actor.typed.scaladsl.adapter._
    systemProvider.classicSystem.toTyped
  }

  import system.executionContext

  private val log = LoggerFactory.getLogger(classOf[DurableStateCleanup])

  private val sharedConfigPath = configPath.replaceAll("""\.cleanup$""", "")
  private val systemConfig: Config = system.settings.config

  private val cleanupSettings = new CleanupSettings(systemConfig.getConfig(configPath))

  private val stateConfig = systemConfig.getConfig(sharedConfigPath + ".state")
  private val stateSettings = StateSettings(stateConfig)
  private val stateDao = DurableStateDao.fromConfig(stateSettings, stateConfig)

  /**
   * Delete the state related to one single `persistenceId`.
   */
  def deleteState(persistenceId: String, resetRevisionNumber: Boolean): Future[Done] = {
    if (resetRevisionNumber) {
      stateDao
        .deleteStateForRevision(persistenceId, revision = 0L)
        .map(_ => Done)(ExecutionContext.parasitic)
    } else {
      stateDao.readState(persistenceId).flatMap {
        case None =>
          Future.successful(Done) // already deleted
        case Some(s) =>
          stateDao
            .deleteStateForRevision(persistenceId, s.revision + 1)
            .map(_ => Done)(ExecutionContext.parasitic)
      }
    }
  }

  /**
   * Delete all states related to the given list of `persistenceIds`.
   */
  def deleteStates(persistenceIds: immutable.Seq[String], resetRevisionNumber: Boolean): Future[Done] = {
    foreach(persistenceIds, "deleteStates", pid => deleteState(pid, resetRevisionNumber))
  }

  private def foreach(
      persistenceIds: immutable.Seq[String],
      operationName: String,
      pidOperation: String => Future[Done]): Future[Done] = {
    val size = persistenceIds.size
    log.info("Cleanup started {} of [{}] persistenceId.", operationName, size: java.lang.Integer)

    def loop(remaining: List[String], n: Int): Future[Done] = {
      remaining match {
        case Nil         => Future.successful(Done)
        case pid :: tail =>
          pidOperation(pid).flatMap { _ =>
            if (n % cleanupSettings.logProgressEvery == 0)
              log.info("Cleanup {} [{}] of [{}].", operationName, n: java.lang.Integer, size: java.lang.Integer)
            loop(tail, n + 1)
          }
      }
    }

    val result = loop(persistenceIds.toList, n = 1)

    result.onComplete {
      case Success(_) =>
        log.info("Cleanup completed {} of [{}] persistenceId.", operationName, size: java.lang.Integer)
      case Failure(e) =>
        log.error(s"Cleanup $operationName failed.", e)
    }

    result
  }
}
