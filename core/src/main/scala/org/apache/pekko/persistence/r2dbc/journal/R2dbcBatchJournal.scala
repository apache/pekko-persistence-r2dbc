/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.persistence.r2dbc.journal

import java.time.Instant

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.JavaDurationOps
import scala.util.{ Failure, Success, Try }

import com.typesafe.config.Config
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import org.apache.pekko
import pekko.Done
import pekko.actor.Timers
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.annotation.InternalApi
import pekko.event.Logging
import pekko.persistence.AtomicWrite
import pekko.persistence.Persistence
import pekko.persistence.PersistentRepr
import pekko.persistence.journal.AsyncWriteJournal
import pekko.persistence.journal.Tagged
import pekko.persistence.r2dbc.Dialect.{ Postgres, Yugabyte }
import pekko.persistence.r2dbc.JournalSettings
import pekko.persistence.r2dbc.internal.InstantFactory
import pekko.persistence.r2dbc.internal.PubSub
import pekko.persistence.r2dbc.journal.JournalDao.SerializedEventMetadata
import pekko.persistence.r2dbc.journal.JournalDao.SerializedJournalRow
import pekko.persistence.typed.PersistenceId
import pekko.serialization.Serialization
import pekko.serialization.SerializationExtension
import pekko.serialization.Serializers
import pekko.stream.scaladsl.Sink

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] object R2dbcBatchJournal {
  private case object Flush
  private case object FlushDone

  private final case class WriteRequest(
      rows: Seq[SerializedJournalRow],
      messages: Seq[AtomicWrite],
      promise: Promise[Seq[Try[Unit]]]
  )
}

/**
 * INTERNAL API
 *
 * Opt-in journal plugin (`pekko.persistence.r2dbc.batched-journal`) that coalesces concurrent
 * writes from different persistence ids into one multi-row statement, trading up to
 * `max-batch-time` of write latency for higher throughput at high concurrency.
 *
 * Mixing persistence ids in a single statement is only safe because the plugin requires
 * `use-app-timestamp = on` and `db-timestamp-monotonic-increasing = on`: in that mode
 * [[JournalDao]] does not bind the per-persistence-id previous sequence number subselect, and
 * timestamps come from the application clock, which therefore must not move backwards.
 * The Postgres and Yugabyte dialects are required because the flush relies on `RETURNING`.
 *
 * Writes are buffered in a bounded queue (`max-queue-size`); incoming writes are rejected with
 * a failed future once the queue is full. Flushed batches are serialized: only one batch is in
 * flight at a time, which keeps same-persistence-id writes committed in order without relying on
 * replay-time coordination, at the cost of not using spare pool capacity. Concurrent flushing can
 * be added later if a single flush saturates.
 *
 * A batch that fails with a database integrity violation is retried in halves so that only the
 * offending persistence ids fail. Infrastructure errors fail the whole batch. A batch can contain
 * an arbitrarily large number of rows when callers use `persistAll` or `persistAsync` bursts, the
 * same as the default journal; this plugin targets many small concurrent writes.
 */
@InternalApi
private[r2dbc] final class R2dbcBatchJournal(config: Config) extends AsyncWriteJournal with Timers {
  import R2dbcJournal.WriteFinished
  import R2dbcJournal.deserializeRow
  import R2dbcBatchJournal.Flush
  import R2dbcBatchJournal.FlushDone
  import R2dbcBatchJournal.WriteRequest

  implicit val system: ActorSystem[?] = context.system.toTyped
  implicit val ec: ExecutionContext = context.dispatcher

  private val log = Logging(context.system, classOf[R2dbcBatchJournal])

  private val persistenceExt = Persistence(system)

  private val serialization: Serialization = SerializationExtension(context.system)
  private val journalSettings = JournalSettings(config)

  require(journalSettings.dialect == Postgres || journalSettings.dialect == Yugabyte,
    "Batching is only supported for Postgres and Yugabyte")
  require(journalSettings.useAppTimestamp, "use-app-timestamp must be 'on' when using R2dbcBatchJournal")
  require(journalSettings.dbTimestampMonotonicIncreasing,
    "db-timestamp-monotonic-increasing must be 'on' when using R2dbcBatchJournal")

  private val maxQueueSize: Int = config.getInt("max-queue-size")
  private val maxBatchSize: Int = config.getInt("max-batch-size")
  private val maxBatchTime: FiniteDuration = config.getDuration("max-batch-time").toScala

  require(maxQueueSize > 0, "max-queue-size must be at least 1 when using R2dbcBatchJournal")
  require(maxBatchSize > 0, "max-batch-size must be at least 1 when using R2dbcBatchJournal")
  require(maxBatchSize <= maxQueueSize, "max-batch-size must be less than or equal to `max-queue-size`")

  private val journalDao = JournalDao.fromConfig(journalSettings, config)

  private val pubSub: Option[PubSub] =
    Option.when(journalSettings.journalPublishEvents)(PubSub(system))

  // if there are pending writes when an actor restarts we must wait for
  // them to complete before we can read the highest sequence number, or we will miss it
  private val writesInProgress = new java.util.HashMap[String, Future[?]]()

  private val queue = collection.mutable.ArrayDeque[WriteRequest]()
  private var noActiveWrite = true

  // set in postStop; failing queued promises completes their futures, whose callbacks must
  // not send WriteFinished to an actor that is already terminating
  @volatile private var stopping = false

  private def doFlush(): Unit = {
    // a pending timer would otherwise flush the next, partial batch early
    timers.cancel(Flush)

    val count = math.min(maxBatchSize, queue.size)
    val writeRequests = new Array[WriteRequest](count)

    queue.copyToArray(writeRequests, 0, count)
    queue.dropInPlace(count)

    def write(requests: Array[WriteRequest]): Future[Unit] =
      journalDao
        .writeEvents(immutable.ArraySeq.unsafeWrapArray(requests.view.flatMap(_.rows).toArray))
        .map { _ =>
          requests.foreach(_.promise.trySuccess(Nil))
          requests.foreach(w => publish(w.messages, Future.successful(w.rows.head.dbTimestamp)))
        }
        .recoverWith {
          case _: R2dbcDataIntegrityViolationException if requests.length > 1 =>
            val (left, right) = requests.splitAt(requests.length / 2)
            write(left).flatMap(_ => write(right))
          case exception =>
            requests.foreach(_.promise.tryFailure(exception))
            Future.unit
        }

    write(writeRequests).onComplete(_ => if (!stopping) self ! FlushDone)
  }

  override def receivePluginInternal: Receive = {
    case WriteFinished(pid, f) => writesInProgress.remove(pid, f)
    case Flush                 =>
      if (noActiveWrite && queue.nonEmpty) {
        noActiveWrite = false
        doFlush()
      }
    case FlushDone =>
      // requests that arrived during the flush have already waited for it, so flush them
      // right away instead of holding them for another max-batch-time
      if (queue.nonEmpty)
        doFlush()
      else
        noActiveWrite = true
  }

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
    if (queue.length >= maxQueueSize)
      Future
        .failed(new IllegalStateException(s"Unable to accept the request, max-queue-size [$maxQueueSize] reached"))
    else {
      val promise = Promise[immutable.Seq[Try[Unit]]]()

      def atomicWrite(atomicWrite: AtomicWrite): Try[Seq[SerializedJournalRow]] = {
        // use-app-timestamp is required, so the timestamp always comes from the application clock
        val timestamp = InstantFactory.now()
        val serialized: Try[Seq[SerializedJournalRow]] = Try {
          atomicWrite.payload.map { pr =>
            val (event, tags) = pr.payload match {
              case Tagged(payload, tags) =>
                (payload.asInstanceOf[AnyRef], tags)
              case other =>
                (other.asInstanceOf[AnyRef], Set.empty[String])
            }

            val entityType = PersistenceId.extractEntityType(pr.persistenceId)
            val slice = persistenceExt.sliceForPersistenceId(pr.persistenceId)

            val serialized = serialization.serialize(event).get
            val serializer = serialization.findSerializerFor(event)
            val manifest = Serializers.manifestFor(serializer, event)
            val id: Int = serializer.identifier

            val metadata = pr.metadata.map { meta =>
              val m = meta.asInstanceOf[AnyRef]
              val serializedMeta = serialization.serialize(m).get
              val metaSerializer = serialization.findSerializerFor(m)
              val metaManifest = Serializers.manifestFor(metaSerializer, m)
              val id: Int = metaSerializer.identifier
              SerializedEventMetadata(id, metaManifest, serializedMeta)
            }

            SerializedJournalRow(
              slice,
              entityType,
              pr.persistenceId,
              pr.sequenceNr,
              timestamp,
              JournalDao.EmptyDbTimestamp,
              Some(serialized),
              id,
              manifest,
              pr.writerUuid,
              tags,
              metadata)
          }
        }

        serialized match {
          case Success(writes) =>
            queue.addOne(WriteRequest(writes, Seq(atomicWrite), promise))

            writesInProgress.put(writes.head.persistenceId, promise.future)
            promise.future.onComplete { _ =>
              if (!stopping) self ! WriteFinished(writes.head.persistenceId, promise.future)
            }

            if (queue.size >= maxBatchSize && noActiveWrite)
              self ! Flush
            else if (!timers.isTimerActive(Flush))
              timers.startSingleTimer(Flush, Flush, maxBatchTime)
          case Failure(exception) =>
            promise.tryFailure(exception)
        }

        serialized
      }

      if (messages.size == 1)
        atomicWrite(messages.head)
      else {
        // persistAsync case
        // easiest to just group all into a single AtomicWrite
        val batch = AtomicWrite(messages.flatMap(_.payload))
        atomicWrite(batch)
      }

      promise.future
    }
  }

  private def publish(messages: immutable.Seq[AtomicWrite], dbTimestamp: Future[Instant]): Future[Done] =
    pubSub match {
      case Some(ps) =>
        dbTimestamp.map { timestamp =>
          messages.iterator
            .flatMap(_.payload.iterator)
            .foreach(pr => ps.publish(pr, timestamp))

          Done
        }

      case None =>
        dbTimestamp.map(_ => Done)(ExecutionContext.parasitic)
    }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    log.debug("asyncDeleteMessagesTo persistenceId [{}], toSequenceNr [{}]", persistenceId, toSequenceNr)
    journalDao.deleteMessagesTo(persistenceId, toSequenceNr)
  }

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
      recoveryCallback: PersistentRepr => Unit): Future[Unit] = {
    log.debug("asyncReplayMessages persistenceId [{}], fromSequenceNr [{}]", persistenceId, fromSequenceNr)
    val effectiveToSequenceNr =
      if (max == Long.MaxValue) toSequenceNr
      else math.min(toSequenceNr, fromSequenceNr + max - 1)
    journalDao
      .internalCurrentEventsByPersistenceId(persistenceId, fromSequenceNr, effectiveToSequenceNr)
      .runWith(Sink.foreach { row =>
        val repr = deserializeRow(serialization, row)
        recoveryCallback(repr)
      })
      .map(_ => ())
  }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    log.debug("asyncReadHighestSequenceNr [{}] [{}]", persistenceId, fromSequenceNr)
    val pendingWrite = Option(writesInProgress.get(persistenceId)) match {
      case Some(f) =>
        log.debug("Write in progress for [{}], deferring highest seq nr until write completed", persistenceId)
        // we only want to make write - replay sequential, not fail if previous write failed
        f.recover { case _ => Done }(ExecutionContext.parasitic)
      case None => Future.successful(Done)
    }
    pendingWrite.flatMap(_ => journalDao.readHighestSequenceNr(persistenceId, fromSequenceNr))
  }

  override def postStop(): Unit = {
    stopping = true
    val cause = new IllegalStateException("Journal actor stopped with pending batched writes")

    queue.foreach(_.promise.tryFailure(cause))
    writesInProgress.clear()
    queue.clear()

    super.postStop()
  }

}
