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

package docs.home

import org.apache.pekko
import pekko.actor.typed.scaladsl.ActorContext
import pekko.persistence.typed.PersistenceId
import pekko.persistence.typed.scaladsl.EventSourcedBehavior
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object RuntimePluginConfigExample {

  type Command
  val context: ActorContext[_] = ???

  // #runtime-plugin-config
  def eventSourcedBehaviorForDatabase(database: String) = {
    val configKey = s"config-for-$database"

    val config: Config =
      ConfigFactory
        .load(
          ConfigFactory
            .parseString(
              s"""
              $configKey = $${pekko.persistence.r2dbc}
              $configKey = {
                connection-factory {
                  database = "$database"
                }

                journal.$configKey.connection-factory = $${$configKey.connection-factory}
                journal.use-connection-factory = "$configKey.connection-factory"
                snapshot.$configKey.connection-factory = $${$configKey.connection-factory}
                snapshot.use-connection-factory = "$configKey.connection-factory"
              }
              """
            )
        )

    (persistenceId: String) =>
      EventSourcedBehavior[Command, String, String](
        PersistenceId.ofUniqueId(persistenceId),
        emptyState = ???,
        commandHandler = ???,
        eventHandler = ???)
        .withJournalPluginId(s"$configKey.journal")
        .withJournalPluginConfig(Some(config))
        .withSnapshotPluginId(s"$configKey.snapshot")
        .withSnapshotPluginConfig(Some(config))
  }

  val eventSourcedBehaviorForDatabase1 = eventSourcedBehaviorForDatabase("database-1")
  context.spawn(eventSourcedBehaviorForDatabase1("persistence-id-1"), "Actor-1")
  context.spawn(eventSourcedBehaviorForDatabase1("persistence-id-2"), "Actor-2")

  val eventSourcedBehaviorForDatabase2 = eventSourcedBehaviorForDatabase("database-2")
  context.spawn(eventSourcedBehaviorForDatabase2("persistence-id-1"), "Actor-3")
  context.spawn(eventSourcedBehaviorForDatabase2("persistence-id-2"), "Actor-4")
  // #runtime-plugin-config
}
