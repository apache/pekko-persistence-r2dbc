/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.projection.r2dbc.internal

import java.util.concurrent.atomic.AtomicLong

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import org.apache.pekko
import pekko.Done
import pekko.NotUsed
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.event.Logging
import pekko.event.LoggingAdapter
import pekko.persistence.query.UpdatedDurableState
import pekko.persistence.query.typed.EventEnvelope
import pekko.persistence.query.typed.scaladsl.LoadEventQuery
import pekko.persistence.r2dbc.internal.R2dbcExecutor
import pekko.persistence.state.scaladsl.DurableStateStore
import pekko.persistence.state.scaladsl.GetObjectResult
import pekko.projection.BySlicesSourceProvider
import pekko.projection.HandlerRecoveryStrategy
import pekko.projection.HandlerRecoveryStrategy.Internal.RetryAndSkip
import pekko.projection.HandlerRecoveryStrategy.Internal.Skip
import pekko.projection.ProjectionContext
import pekko.projection.ProjectionId
import pekko.projection.RunningProjection
import pekko.projection.RunningProjection.AbortProjectionException
import pekko.projection.RunningProjectionManagement
import pekko.projection.StatusObserver
import pekko.projection.internal.ActorHandlerInit
import pekko.projection.internal.AtLeastOnce
import pekko.projection.internal.AtMostOnce
import pekko.projection.internal.ExactlyOnce
import pekko.projection.internal.GroupedHandlerStrategy
import pekko.projection.internal.HandlerStrategy
import pekko.projection.internal.InternalProjection
import pekko.projection.internal.InternalProjectionState
import pekko.projection.internal.ManagementState
import pekko.projection.internal.OffsetStrategy
import pekko.projection.internal.ProjectionContextImpl
import pekko.projection.internal.ProjectionSettings
import pekko.projection.internal.SettingsImpl
import pekko.projection.javadsl
import pekko.projection.r2dbc.R2dbcProjectionSettings
import pekko.projection.r2dbc.scaladsl.R2dbcHandler
import pekko.projection.r2dbc.scaladsl.R2dbcSession
import pekko.projection.scaladsl
import pekko.projection.scaladsl.Handler
import pekko.projection.scaladsl.SourceProvider
import pekko.stream.RestartSettings
import pekko.stream.scaladsl.FlowWithContext
import pekko.stream.scaladsl.Source
import io.r2dbc.spi.ConnectionFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * INTERNAL API
 */
@InternalApi
private[projection] object R2dbcProjectionImpl {
  val log: Logger = LoggerFactory.getLogger(classOf[R2dbcProjectionImpl[_, _]])

  private val FutureDone: Future[Done] = Future.successful(Done)

  private[projection] def createOffsetStore(
      projectionId: ProjectionId,
      sourceProvider: Option[BySlicesSourceProvider],
      settings: R2dbcProjectionSettings,
      connectionFactory: ConnectionFactory)(implicit system: ActorSystem[_]) = {
    val r2dbcExecutor =
      new R2dbcExecutor(connectionFactory, log, settings.logDbCallsExceeding)(system.executionContext, system)
    R2dbcOffsetStore.fromConfig(projectionId, sourceProvider, system, settings, r2dbcExecutor)
  }

  private val loadEnvelopeCounter = new AtomicLong

  def loadEnvelope[Envelope](env: Envelope, sourceProvider: SourceProvider[_, Envelope])(implicit
      ec: ExecutionContext): Future[Envelope] = {
    env match {
      case eventEnvelope: EventEnvelope[_] if eventEnvelope.eventOption.isEmpty && !skipEnvelope(eventEnvelope) =>
        val pid = eventEnvelope.persistenceId
        val seqNr = eventEnvelope.sequenceNr
        (sourceProvider match {
          case loadEventQuery: LoadEventQuery =>
            loadEventQuery.loadEnvelope[Any](pid, seqNr)
          case loadEventQuery: pekko.persistence.query.typed.javadsl.LoadEventQuery =>
            import pekko.util.FutureConverters._
            loadEventQuery.loadEnvelope[Any](pid, seqNr).asScala
          case _ =>
            throw new IllegalArgumentException(
              s"Expected sourceProvider [${sourceProvider.getClass.getName}] " +
              "to implement LoadEventQuery when used with eventsBySlices.")
        }).map { loadedEnv =>
          val count = loadEnvelopeCounter.incrementAndGet()
          if (count % 1000 == 0)
            log.info("Loaded event lazily, persistenceId [{}], seqNr [{}]. Load count [{}]", pid, seqNr: java.lang.Long,
              count: java.lang.Long)
          else
            log.debug("Loaded event lazily, persistenceId [{}], seqNr [{}]. Load count [{}]", pid,
              seqNr: java.lang.Long,
              count: java.lang.Long)
          loadedEnv.asInstanceOf[Envelope]
        }

      case upd: UpdatedDurableState[_] if upd.value == null =>
        val pid = upd.persistenceId
        val revision = upd.revision
        (sourceProvider match {
          case store: DurableStateStore[_] =>
            store.getObject(pid)
          case store: pekko.persistence.state.javadsl.DurableStateStore[_] =>
            import pekko.util.FutureConverters._
            store.getObject(pid).asScala.map(_.toScala)
        }).map {
          case GetObjectResult(Some(loadedValue), loadedRevision) =>
            val count = loadEnvelopeCounter.incrementAndGet()
            if (count % 1000 == 0)
              log.info(
                "Loaded durable state lazily, persistenceId [{}], revision [{}]. Load count [{}]",
                pid,
                loadedRevision: java.lang.Long,
                count: java.lang.Long)
            else
              log.debug(
                "Loaded durable state lazily, persistenceId [{}], revision [{}]. Load count [{}]",
                pid,
                loadedRevision: java.lang.Long,
                count: java.lang.Long)
            new UpdatedDurableState(pid, loadedRevision, loadedValue, upd.offset, upd.timestamp)
              .asInstanceOf[Envelope]
          case GetObjectResult(None, _) =>
            // FIXME use DeletedDurableState here when that is added
            throw new IllegalStateException(
              s"Durable state not found when loaded lazily, persistenceId [$pid], revision [$revision]")
        }

      case _ =>
        Future.successful(env)
    }
  }

  private[projection] def adaptedHandlerForExactlyOnce[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      handlerFactory: () => R2dbcHandler[Envelope],
      offsetStore: R2dbcOffsetStore,
      r2dbcExecutor: R2dbcExecutor)(implicit ec: ExecutionContext, system: ActorSystem[_]): () => Handler[Envelope] = {
    () =>
      new AdaptedR2dbcHandler(handlerFactory()) {
        override def process(envelope: Envelope): Future[Done] = {
          offsetStore.isAccepted(envelope).flatMap {
            case true =>
              if (skipEnvelope(envelope)) {
                val offset = sourceProvider.extractOffset(envelope)
                offsetStore.saveOffset(offset)
              } else {
                loadEnvelope(envelope, sourceProvider).flatMap { loadedEnvelope =>
                  val offset = sourceProvider.extractOffset(loadedEnvelope)
                  r2dbcExecutor.withConnection("exactly-once handler") { conn =>
                    // run users handler
                    val session = new R2dbcSession(conn)
                    delegate
                      .process(session, loadedEnvelope)
                      .flatMap { _ =>
                        offsetStore.saveOffsetInTx(conn, offset)
                      }
                  }
                }
              }
            case false =>
              FutureDone
          }
        }
      }
  }

  private[projection] def adaptedHandlerForGrouped[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      handlerFactory: () => R2dbcHandler[immutable.Seq[Envelope]],
      offsetStore: R2dbcOffsetStore,
      r2dbcExecutor: R2dbcExecutor)(
      implicit
      ec: ExecutionContext,
      system: ActorSystem[_]): () => Handler[immutable.Seq[Envelope]] = { () =>
    new AdaptedR2dbcHandler(handlerFactory()) {
      override def process(envelopes: immutable.Seq[Envelope]): Future[Done] = {
        offsetStore.filterAccepted(envelopes).flatMap { acceptedEnvelopes =>
          if (acceptedEnvelopes.isEmpty) {
            FutureDone
          } else {
            Future.sequence(acceptedEnvelopes.map(env => loadEnvelope(env, sourceProvider))).flatMap {
              loadedEnvelopes =>
                val offsets = loadedEnvelopes.iterator.map(sourceProvider.extractOffset).toVector
                val filteredEnvelopes = loadedEnvelopes.filterNot(skipEnvelope)
                if (filteredEnvelopes.isEmpty) {
                  offsetStore.saveOffsets(offsets)
                } else {
                  r2dbcExecutor.withConnection("grouped handler") { conn =>
                    // run users handler
                    val session = new R2dbcSession(conn)
                    delegate.process(session, filteredEnvelopes).flatMap { _ =>
                      offsetStore.saveOffsetsInTx(conn, offsets)
                    }
                  }
                }
            }
          }
        }
      }
    }
  }

  private[projection] def adaptedHandlerForAtLeastOnce[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      handlerFactory: () => R2dbcHandler[Envelope],
      offsetStore: R2dbcOffsetStore,
      r2dbcExecutor: R2dbcExecutor)(implicit ec: ExecutionContext, system: ActorSystem[_]): () => Handler[Envelope] = {
    () =>
      new AdaptedR2dbcHandler(handlerFactory()) {
        override def process(envelope: Envelope): Future[Done] = {
          offsetStore.isAccepted(envelope).flatMap {
            case true =>
              if (skipEnvelope(envelope)) {
                offsetStore.addInflight(envelope)
                FutureDone
              } else {
                loadEnvelope(envelope, sourceProvider).flatMap { loadedEnvelope =>
                  r2dbcExecutor
                    .withConnection("at-least-once handler") { conn =>
                      // run users handler
                      val session = new R2dbcSession(conn)
                      delegate.process(session, loadedEnvelope)
                    }
                    .map { _ =>
                      offsetStore.addInflight(loadedEnvelope)
                      Done
                    }
                }
              }
            case false =>
              FutureDone
          }
        }
      }
  }

  private[projection] def adaptedHandlerForAtLeastOnceAsync[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      handlerFactory: () => Handler[Envelope],
      offsetStore: R2dbcOffsetStore)(implicit ec: ExecutionContext, system: ActorSystem[_]): () => Handler[Envelope] = {
    () =>
      new AdaptedHandler(handlerFactory()) {
        override def process(envelope: Envelope): Future[Done] = {
          offsetStore.isAccepted(envelope).flatMap {
            case true =>
              if (skipEnvelope(envelope)) {
                offsetStore.addInflight(envelope)
                FutureDone
              } else {
                loadEnvelope(envelope, sourceProvider).flatMap { loadedEnvelope =>
                  delegate
                    .process(loadedEnvelope)
                    .map { _ =>
                      offsetStore.addInflight(loadedEnvelope)
                      Done
                    }
                }
              }
            case false =>
              FutureDone
          }
        }
      }
  }

  private[projection] def adaptedHandlerForGroupedAsync[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      handlerFactory: () => Handler[immutable.Seq[Envelope]],
      offsetStore: R2dbcOffsetStore)(
      implicit
      ec: ExecutionContext,
      system: ActorSystem[_]): () => Handler[immutable.Seq[Envelope]] = { () =>
    new AdaptedHandler(handlerFactory()) {
      override def process(envelopes: immutable.Seq[Envelope]): Future[Done] = {
        offsetStore.filterAccepted(envelopes).flatMap { acceptedEnvelopes =>
          if (acceptedEnvelopes.isEmpty) {
            FutureDone
          } else {
            Future.sequence(acceptedEnvelopes.map(env => loadEnvelope(env, sourceProvider))).flatMap {
              loadedEnvelopes =>
                val filteredEnvelopes = loadedEnvelopes.filterNot(skipEnvelope)
                if (filteredEnvelopes.isEmpty) {
                  offsetStore.addInflights(loadedEnvelopes)
                  FutureDone
                } else {
                  delegate
                    .process(filteredEnvelopes)
                    .map { _ =>
                      offsetStore.addInflights(loadedEnvelopes)
                      Done
                    }
                }
            }
          }
        }
      }
    }
  }

  private[projection] def adaptedHandlerForFlow[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      handler: FlowWithContext[Envelope, ProjectionContext, Done, ProjectionContext, _],
      offsetStore: R2dbcOffsetStore)(
      implicit
      ec: ExecutionContext,
      system: ActorSystem[_]): FlowWithContext[Envelope, ProjectionContext, Done, ProjectionContext, _] = {

    FlowWithContext[Envelope, ProjectionContext]
      .mapAsync(1) { env =>
        offsetStore
          .isAccepted(env)
          .flatMap { ok =>
            if (ok) {
              if (skipEnvelope(env)) {
                log.info("atLeastOnceFlow doesn't support of skipping envelopes. Envelope [{}] still emitted.", env)
              }
              loadEnvelope(env, sourceProvider).map { loadedEnvelope =>
                offsetStore.addInflight(loadedEnvelope)
                Some(loadedEnvelope)
              }
            } else {
              Future.successful(None)
            }
          }
      }
      .collect { case Some(env) =>
        env
      }
      .via(handler)
  }

  abstract class AdaptedR2dbcHandler[E](val delegate: R2dbcHandler[E])(
      implicit
      ec: ExecutionContext,
      system: ActorSystem[_])
      extends Handler[E] {

    override def start(): Future[Done] =
      delegate.start()

    override def stop(): Future[Done] =
      delegate.stop()
  }

  abstract class AdaptedHandler[E](val delegate: Handler[E])(implicit ec: ExecutionContext, system: ActorSystem[_])
      extends Handler[E] {

    override def start(): Future[Done] =
      delegate.start()

    override def stop(): Future[Done] =
      delegate.stop()
  }

  private def skipEnvelope[Envelope](env: Envelope): Boolean = {
    env match {
      case e: EventEnvelope[_] =>
        e.eventMetadata match {
          case Some(NotUsed) => true
          case _             => false
        }
      case _ => false
    }
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[projection] class R2dbcProjectionImpl[Offset, Envelope](
    val projectionId: ProjectionId,
    r2dbcSettings: R2dbcProjectionSettings,
    settingsOpt: Option[ProjectionSettings],
    sourceProvider: SourceProvider[Offset, Envelope],
    restartBackoffOpt: Option[RestartSettings],
    val offsetStrategy: OffsetStrategy,
    handlerStrategy: HandlerStrategy,
    override val statusObserver: StatusObserver[Envelope],
    offsetStore: R2dbcOffsetStore)
    extends scaladsl.ExactlyOnceProjection[Offset, Envelope]
    with javadsl.ExactlyOnceProjection[Offset, Envelope]
    with scaladsl.GroupedProjection[Offset, Envelope]
    with javadsl.GroupedProjection[Offset, Envelope]
    with scaladsl.AtLeastOnceProjection[Offset, Envelope]
    with javadsl.AtLeastOnceProjection[Offset, Envelope]
    with scaladsl.AtLeastOnceFlowProjection[Offset, Envelope]
    with javadsl.AtLeastOnceFlowProjection[Offset, Envelope]
    with SettingsImpl[R2dbcProjectionImpl[Offset, Envelope]]
    with InternalProjection {

  private def copy(
      settingsOpt: Option[ProjectionSettings] = this.settingsOpt,
      restartBackoffOpt: Option[RestartSettings] = this.restartBackoffOpt,
      offsetStrategy: OffsetStrategy = this.offsetStrategy,
      handlerStrategy: HandlerStrategy = this.handlerStrategy,
      statusObserver: StatusObserver[Envelope] = this.statusObserver): R2dbcProjectionImpl[Offset, Envelope] =
    new R2dbcProjectionImpl(
      projectionId,
      r2dbcSettings,
      settingsOpt,
      sourceProvider,
      restartBackoffOpt,
      offsetStrategy,
      handlerStrategy,
      statusObserver,
      offsetStore)

  type ReadOffset = () => Future[Option[Offset]]

  /*
   * Build the final ProjectionSettings to use, if currently set to None fallback to values in config file
   */
  private def settingsOrDefaults(implicit system: ActorSystem[_]): ProjectionSettings = {
    val settings = settingsOpt.getOrElse(ProjectionSettings(system))
    restartBackoffOpt match {
      case None    => settings
      case Some(r) => settings.copy(restartBackoff = r)
    }
  }

  override def withRestartBackoffSettings(restartBackoff: RestartSettings): R2dbcProjectionImpl[Offset, Envelope] =
    copy(restartBackoffOpt = Some(restartBackoff))

  override def withSaveOffset(
      afterEnvelopes: Int,
      afterDuration: FiniteDuration): R2dbcProjectionImpl[Offset, Envelope] =
    copy(offsetStrategy = offsetStrategy
      .asInstanceOf[AtLeastOnce]
      .copy(afterEnvelopes = Some(afterEnvelopes), orAfterDuration = Some(afterDuration)))

  override def withGroup(
      groupAfterEnvelopes: Int,
      groupAfterDuration: FiniteDuration): R2dbcProjectionImpl[Offset, Envelope] =
    copy(handlerStrategy = handlerStrategy
      .asInstanceOf[GroupedHandlerStrategy[Envelope]]
      .copy(afterEnvelopes = Some(groupAfterEnvelopes), orAfterDuration = Some(groupAfterDuration)))

  override def withRecoveryStrategy(
      recoveryStrategy: HandlerRecoveryStrategy): R2dbcProjectionImpl[Offset, Envelope] = {
    val newStrategy = offsetStrategy match {
      case s: ExactlyOnce => s.copy(recoveryStrategy = Some(recoveryStrategy))
      case s: AtLeastOnce => s.copy(recoveryStrategy = Some(recoveryStrategy))
      // NOTE: AtMostOnce has its own withRecoveryStrategy variant
      // this method is not available for AtMostOnceProjection
      case s: AtMostOnce => s
    }
    copy(offsetStrategy = newStrategy)
  }

  override def withStatusObserver(observer: StatusObserver[Envelope]): R2dbcProjectionImpl[Offset, Envelope] =
    copy(statusObserver = observer)

  private[projection] def actorHandlerInit[T]: Option[ActorHandlerInit[T]] =
    handlerStrategy.actorHandlerInit

  /**
   * INTERNAL API Return a RunningProjection
   */
  override private[projection] def run()(implicit system: ActorSystem[_]): RunningProjection =
    new R2dbcInternalProjectionState(settingsOrDefaults).newRunningInstance()

  /**
   * INTERNAL API
   *
   * This method returns the projection Source mapped with user 'handler' function, but before any sink attached. This
   * is mainly intended to be used by the TestKit allowing it to attach a TestSink to it.
   */
  override private[projection] def mappedSource()(implicit system: ActorSystem[_]): Source[Done, Future[Done]] =
    new R2dbcInternalProjectionState(settingsOrDefaults).mappedSource()

  private class R2dbcInternalProjectionState(settings: ProjectionSettings)(implicit val system: ActorSystem[_])
      extends InternalProjectionState[Offset, Envelope](
        projectionId,
        sourceProvider,
        offsetStrategy,
        handlerStrategy,
        statusObserver,
        settings) {

    implicit val executionContext: ExecutionContext = system.executionContext
    override val logger: LoggingAdapter = Logging(system.classicSystem, classOf[R2dbcInternalProjectionState])

    private val isExactlyOnceWithSkip: Boolean =
      offsetStrategy match {
        case ExactlyOnce(Some(Skip)) | ExactlyOnce(Some(_: RetryAndSkip)) => true
        case _                                                            => false
      }

    override def readPaused(): Future[Boolean] =
      offsetStore.readManagementState().map(_.exists(_.paused))

    override def readOffsets(): Future[Option[Offset]] =
      offsetStore.readOffset()

    // Called from InternalProjectionState.saveOffsetAndReport
    override def saveOffset(projectionId: ProjectionId, offset: Offset): Future[Done] =
      offsetStore.saveOffset(offset)

    override protected def saveOffsetAndReport(
        projectionId: ProjectionId,
        projectionContext: ProjectionContextImpl[Offset, Envelope],
        batchSize: Int): Future[Done] = {
      import R2dbcProjectionImpl.FutureDone
      val envelope = projectionContext.envelope

      if (offsetStore.isInflight(envelope) || isExactlyOnceWithSkip) {
        super.saveOffsetAndReport(projectionId, projectionContext, batchSize)
      } else {
        FutureDone
      }
    }

    override protected def saveOffsetsAndReport(
        projectionId: ProjectionId,
        batch: immutable.Seq[ProjectionContextImpl[Offset, Envelope]]): Future[Done] = {
      import R2dbcProjectionImpl.FutureDone

      val acceptedContexts =
        if (isExactlyOnceWithSkip)
          batch.toVector
        else {
          batch.iterator.filter { ctx =>
            val env = ctx.envelope
            offsetStore.isInflight(env)
          }.toVector
        }

      if (acceptedContexts.isEmpty) {
        FutureDone
      } else {
        val offsets = acceptedContexts.map(_.offset)
        offsetStore
          .saveOffsets(offsets)
          .map { done =>
            val batchSize = acceptedContexts.map { _.groupSize }.sum
            val last = acceptedContexts.last
            try {
              statusObserver.offsetProgress(projectionId, last.envelope)
            } catch {
              case NonFatal(_) => // ignore
            }
            getTelemetry().onOffsetStored(batchSize)
            done
          }
      }
    }

    private[projection] def newRunningInstance(): RunningProjection =
      new R2dbcRunningProjection(RunningProjection.withBackoff(() => this.mappedSource(), settings), this)
  }

  private class R2dbcRunningProjection(source: Source[Done, _], projectionState: R2dbcInternalProjectionState)(implicit
      system: ActorSystem[_])
      extends RunningProjection
      with RunningProjectionManagement[Offset] {

    private val streamDone = source.run()

    override def stop(): Future[Done] = {
      projectionState.killSwitch.shutdown()
      // if the handler is retrying it will be aborted by this,
      // otherwise the stream would not be completed by the killSwitch until after all retries
      projectionState.abort.failure(AbortProjectionException)
      streamDone
    }

    // RunningProjectionManagement
    override def getOffset(): Future[Option[Offset]] = {
      offsetStore.getOffset()
    }

    // RunningProjectionManagement
    override def setOffset(offset: Option[Offset]): Future[Done] = {
      offset match {
        case Some(o) => offsetStore.managementSetOffset(o)
        case None    => offsetStore.managementClearOffset()
      }
    }

    // RunningProjectionManagement
    override def getManagementState(): Future[Option[ManagementState]] =
      offsetStore.readManagementState()

    // RunningProjectionManagement
    override def setPaused(paused: Boolean): Future[Done] =
      offsetStore.savePaused(paused)
  }

}
