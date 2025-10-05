/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package docs.home.projection

import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.persistence.query.Offset
import pekko.persistence.query.typed.EventEnvelope
import pekko.projection.ProjectionId
import pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import pekko.projection.r2dbc.scaladsl.R2dbcProjection
import pekko.projection.scaladsl.AtLeastOnceProjection
import com.typesafe.config.ConfigFactory
import docs.home.RuntimePluginConfigExample.EventSourcedBehaviorForDatabase
import docs.home.RuntimePluginConfigExample._

object RuntimePluginConfigCompatibilityExample {

  implicit val system: ActorSystem[_] = ???
  val entityType: String = ???
  val projectionId: ProjectionId = ???
  val sliceRange: Range = ???

  // #runtime-plugin-config-compatibility
  def atLeastOnceProjection(
      eventSourcedBehavior: EventSourcedBehaviorForDatabase,
      sliceRange: Range
  ): AtLeastOnceProjection[Offset, EventEnvelope[Event]] = {
    val sourceProvider = EventSourcedProvider
      .eventsBySlices[Event](
        system = system,
        readJournalPluginId = s"${eventSourcedBehavior.configKey}.query",
        readJournalConfig = eventSourcedBehavior.config,
        entityType = entityType,
        minSlice = sliceRange.min,
        maxSlice = sliceRange.max)
    val projectionConfig = ConfigFactory
      .load(
        eventSourcedBehavior.config
          .withFallback(
            ConfigFactory
              .parseString(
                s"""
              pekko.projection.r2dbc.use-connection-factory = "${eventSourcedBehavior.configKey}.connection-factory"
              """
              )
          )
      )
    R2dbcProjection
      .atLeastOnce(
        projectionId = projectionId,
        config = projectionConfig,
        settings = None,
        sourceProvider = sourceProvider,
        handler = () => ???
      )
  }

  atLeastOnceProjection(eventSourcedBehaviorForDatabase1, sliceRange)
  atLeastOnceProjection(eventSourcedBehaviorForDatabase2, sliceRange)
  // #runtime-plugin-config-compatibility
}
