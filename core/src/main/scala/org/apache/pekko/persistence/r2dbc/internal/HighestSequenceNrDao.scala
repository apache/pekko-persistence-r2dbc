package org.apache.pekko.persistence.r2dbc.internal

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.dispatch.ExecutionContexts
import pekko.persistence.r2dbc.Dialect
import pekko.persistence.r2dbc.internal.Sql.DialectInterpolation
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] object HighestSequenceNrDao {

  private val log = LoggerFactory.getLogger(classOf[HighestSequenceNrDao])
}

/**
 * INTERNAL API
 */
@InternalApi
trait HighestSequenceNrDao {
  import HighestSequenceNrDao._

  implicit protected def ec: ExecutionContext

  implicit protected def dialect: Dialect

  protected def journalTable: String

  protected def r2dbcExecutor: R2dbcExecutor

  // TODO try dropping lazy
  private lazy val selectHighestSequenceNrSql = sql"""
    SELECT MAX(seq_nr) from $journalTable
    WHERE persistence_id = ? AND seq_nr >= ?"""

  def readHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    val result = r2dbcExecutor
      .select(s"select highest seqNr [$persistenceId]")(
        connection =>
          connection
            .createStatement(selectHighestSequenceNrSql)
            .bind(0, persistenceId)
            .bind(1, fromSequenceNr),
        row => {
          val seqNr = row.get[java.lang.Long](0, classOf[java.lang.Long])
          if (seqNr eq null) 0L else seqNr.longValue
        })
      .map(r => if (r.isEmpty) 0L else r.head)(ExecutionContexts.parasitic)

    if (log.isDebugEnabled)
      result.foreach(seqNr => log.debug("Highest sequence nr for persistenceId [{}]: [{}]", persistenceId, seqNr))

    result
  }
}
