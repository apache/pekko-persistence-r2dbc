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

import java.time.Instant

import org.apache.pekko
import pekko.NotUsed
import pekko.annotation.InternalApi
import pekko.persistence.query.TimestampOffset.toTimestampOffset
import pekko.persistence.query.typed.EventEnvelope
import pekko.stream.Attributes
import pekko.stream.Outlet
import pekko.stream.SourceShape
import pekko.stream.scaladsl.Keep
import pekko.stream.scaladsl.Source
import pekko.stream.stage.GraphStage
import pekko.stream.stage.GraphStageLogic
import pekko.stream.stage.InHandler
import pekko.stream.stage.OutHandler

/**
 * INTERNAL API
 */
@InternalApi private[r2dbc] class StartingFromSnapshotStage[Event](
    snapshotSource: Source[EventEnvelope[Event], NotUsed],
    primarySource: Map[String, (Long, Instant)] => Source[EventEnvelope[Event], NotUsed])
    extends GraphStage[SourceShape[EventEnvelope[Event]]] {

  val out: Outlet[EventEnvelope[Event]] = Outlet("out")

  override val shape: SourceShape[EventEnvelope[Event]] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) { self =>
      setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit = {
            val snapshotHandler = new SnapshotHandler
            setHandler(out, snapshotHandler)

            subFusingMaterializer.materialize(
              snapshotSource.toMat(snapshotHandler.subSink.sink)(Keep.left),
              inheritedAttributes)
          }
        })

      class SnapshotHandler extends OutHandler with InHandler {
        private var snapshotOffsets = Map.empty[String, (Long, Instant)]

        val subSink = new SubSinkInlet[EventEnvelope[Event]]("snapshots")
        subSink.pull()
        subSink.setHandler(this)

        override def onPull(): Unit = {
          subSink.pull()
        }

        override def onPush(): Unit = {
          val env = subSink.grab()
          snapshotOffsets =
            snapshotOffsets.updated(env.persistenceId, env.sequenceNr -> toTimestampOffset(env.offset).timestamp)
          push(out, env)
        }

        override def onUpstreamFinish(): Unit = {
          val primaryHandler = new PrimaryHandler(isAvailable(out))
          self.setHandler(out, primaryHandler)

          subFusingMaterializer.materialize(
            primarySource(snapshotOffsets).toMat(primaryHandler.subSink.sink)(Keep.left),
            inheritedAttributes)
        }

        override def onDownstreamFinish(cause: Throwable): Unit = {
          subSink.cancel(cause)
          completeStage()
        }
      }

      class PrimaryHandler(pullImmediately: Boolean) extends OutHandler with InHandler {
        val subSink = new SubSinkInlet[EventEnvelope[Event]]("primary")
        if (pullImmediately) subSink.pull()
        subSink.setHandler(this)

        override def onPull(): Unit = {
          subSink.pull()
        }

        override def onPush(): Unit = {
          push(out, subSink.grab())
        }

        override def onDownstreamFinish(cause: Throwable): Unit = {
          subSink.cancel(cause)
          completeStage()
        }
      }

    }

}
