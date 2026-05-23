/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package docs.home.state

// #change-handler
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.Persistence
import org.apache.pekko.persistence.query.DeletedDurableState
import org.apache.pekko.persistence.query.DurableStateChange
import org.apache.pekko.persistence.query.UpdatedDurableState
import org.apache.pekko.persistence.r2dbc.session.scaladsl.R2dbcSession
import org.apache.pekko.persistence.r2dbc.state.scaladsl.ChangeHandler

// #change-handler

/* config:
// #change-handler-config
pekko.persistence.r2dbc.state {
  change-handler {
    "BlogPost" = "docs.BlogPostCounts"
  }
}
// #change-handler-config
 */

// #change-handler
/**
 * Keep track of number of published blog posts. Count per slice.
 *
 * {{{
 * CREATE TABLE post_count (slice INT NOT NULL, cnt BIGINT NOT NULL, PRIMARY KEY(slice));
 * }}}
 */
class BlogPostCounts(system: ActorSystem[_]) extends ChangeHandler[BlogPost.State] {

  private val incrementSql =
    "INSERT INTO post_count (slice, cnt) VALUES ($1, 1) " +
    "ON CONFLICT (slice) DO UPDATE SET cnt = excluded.cnt + 1"

  private val decrementSql =
    "UPDATE post_count SET cnt = cnt - 1 WHERE slice = $1"

  private implicit val ec: ExecutionContext = system.executionContext

  override def process(session: R2dbcSession, change: DurableStateChange[BlogPost.State]): Future[Done] = {
    change match {
      case upd: UpdatedDurableState[BlogPost.State] =>
        upd.value match {
          case _: BlogPost.PublishedState =>
            val slice = Persistence(system).sliceForPersistenceId(upd.persistenceId)
            val stmt = session
              .createStatement(incrementSql)
              .bind(0, slice)
            session.updateOne(stmt).map(_ => Done)
          case _ =>
            Future.successful(Done)
        }

      case del: DeletedDurableState[BlogPost.State] =>
        val slice = Persistence(system).sliceForPersistenceId(del.persistenceId)
        val stmt = session
          .createStatement(decrementSql)
          .bind(0, slice)
        session.updateOne(stmt).map(_ => Done)
    }
  }
}
// #change-handler
