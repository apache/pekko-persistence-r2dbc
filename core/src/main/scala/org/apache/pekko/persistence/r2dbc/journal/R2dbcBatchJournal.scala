/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.journal

import java.time.Instant
import java.time.temporal.ChronoUnit

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.jdk.DurationConverters.JavaDurationOps
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

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
  private[r2dbc] final case class FlushDone(generation: Long)

  // the promise is completed with Done only after the batch containing this request is committed;
  // the AsyncWriteJournal result is derived from it at the API boundary
  private final case class WriteRequest(
      rows: Seq[SerializedJournalRow],
      messages: Seq[AtomicWrite],
      promise: Promise[Done]
  )

  private val generationCounter = new java.util.concurrent.atomic.AtomicLong(0L)
  private[r2dbc] def nextGeneration(): Long = generationCounter.incrementAndGet()

  private val lastTimestampMicros = new java.util.concurrent.atomic.AtomicLong(0L)
  private[r2dbc] def nextTimestamp(): Instant = {
    val nowMicros = ChronoUnit.MICROS.between(Instant.EPOCH, InstantFactory.now())
    val next = lastTimestampMicros.updateAndGet(prev => math.max(prev + 1, nowMicros))
    Instant.EPOCH.plus(next, ChronoUnit.MICROS)
  }
}

/**
 * INTERNAL API
 *
 * Opt-in journal plugin (`pekko.persistence.r2dbc.batched-journal`) that coalesces concurrent
 * writes from different persistence ids into one transaction, trading up to
 * `max-batch-time` of write latency for higher throughput at high concurrency.
 *
 * Mixing persistence ids in a single statement is only safe because the plugin requires
 * `use-app-timestamp = on` and `db-timestamp-monotonic-increasing = on`: in that mode
 * [[JournalDao]] does not bind the per-persistence-id previous sequence number subselect, and
 * timestamps come from the application clock, which therefore must not move backwards.
 * Batching is only supported and tested for the Postgres and Yugabyte dialects.
 *
 * Writes are buffered in a bounded queue (`max-queue-size`); incoming writes are rejected per
 * message once the queue is full. The rejection is returned in the per-message `Try` results,
 * not as a failed `Future`, so a full queue does not count toward the journal circuit breaker.
 * Flushed batches are serialized: only one flush is in flight at a time, which keeps
 * same-persistence-id writes committed in order without relying on replay-time coordination, at
 * the cost of not using spare pool capacity. Concurrent flushing can be added later if a single
 * flush saturates.
 *
 * Each write request is stamped at flush time with the application clock truncated to
 * microseconds and bumped to stay strictly increasing within this journal actor. Equal
 * `db_timestamp` values therefore never span more than one write request within this actor, so
 * the `eventsBySlices` query can page through any batch regardless of its buffer size. The
 * stamps can lead the wall clock by at most `max-batch-size` microseconds per flush. A single
 * request can still contain many events when the caller uses `persistAll` or `persistAsync`
 * bursts, the same as the default journal.
 *
 * A batch that fails with a database integrity violation is retried in halves so that only the
 * offending persistence ids fail. Infrastructure errors fail the whole batch. The retried
 * halves are written concurrently and can use several pool connections at once. Each half
 * re-stamps its requests with new, later timestamps. A single split preserves order, but if the
 * half holding the earlier sequence numbers is retried after its sibling committed, its
 * re-stamped rows can invert the `db_timestamp` order of two same-persistence-id writes.
 * Replay still orders by sequence number; only timestamp-ordered read sides see the inversion.
 * This plugin targets many small concurrent writes.
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
  require(maxBatchTime > Duration.Zero, "max-batch-time must be greater than zero when using R2dbcBatchJournal")

  private val generation = R2dbcBatchJournal.nextGeneration()

  private val journalDao = JournalDao.fromConfig(journalSettings, config)

  private val pubSub: Option[PubSub] =
    Option.when(journalSettings.journalPublishEvents)(PubSub(system))

  // if there are pending writes when an actor restarts we must wait for
  // them to complete before we can read the highest sequence number, or we will miss it
  private val writesInProgress = new java.util.HashMap[String, Future[?]]()

  private val queue = collection.mutable.ArrayDeque[WriteRequest]()
  private var noActiveWrite = true

  private def doFlush(): Unit = {
    // a pending timer would otherwise flush the next, partial batch early
    timers.cancel(Flush)

    val count = math.min(maxBatchSize, queue.size)
    val writeRequests = queue.take(count).toVector
    queue.dropInPlace(count)
    log.debug("flushing [{}] write requests", count)

    def write(requests: Vector[WriteRequest]): Future[Unit] = {
      val stampedRequests = requests.map(request => request -> R2dbcBatchJournal.nextTimestamp())

      journalDao
        .writeEvents(stampedRequests.flatMap {
          case (request, timestamp) => request.rows.map(_.copy(dbTimestamp = timestamp))
        })
        .map { _ =>
          requests.foreach(_.promise.trySuccess(Done))
          publish(stampedRequests)
        }
        .recoverWith {
          case _: R2dbcDataIntegrityViolationException if requests.size > 1 =>
            val (left, right) = requests.splitAt(requests.size / 2)
            write(left).zipWith(write(right))((_, _) => ())
          case exception =>
            requests.foreach(_.promise.tryFailure(exception))
            Future.unit
        }
    }

    write(writeRequests).onComplete(_ => self ! FlushDone(generation))
  }

  override def receivePluginInternal: Receive = {
    case WriteFinished(pid, f) => writesInProgress.remove(pid, f)
    case Flush                 =>
      if (noActiveWrite && queue.nonEmpty) {
        noActiveWrite = false
        doFlush()
      }
    case FlushDone(g) if g == generation =>
      noActiveWrite = true
      if (queue.size >= maxBatchSize) {
        noActiveWrite = false
        doFlush()
      } else if (queue.nonEmpty && !timers.isTimerActive(Flush)) {
        timers.startSingleTimer(Flush, Flush, maxBatchTime)
      }
  }

  override def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] = {
    if (queue.length >= maxQueueSize) {
      val queueFullFailure =
        Failure(new IllegalStateException(s"Unable to accept the request, max-queue-size [$maxQueueSize] reached"))
      Future.successful(messages.map(_ => queueFullFailure))
    } else {
      val promise = Promise[Done]()

      def atomicWrite(atomicWrite: AtomicWrite): Try[Seq[SerializedJournalRow]] = {
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
              JournalDao.EmptyDbTimestamp,
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
              self ! WriteFinished(writes.head.persistenceId, promise.future)
            }

            if (queue.size >= maxBatchSize && noActiveWrite) {
              noActiveWrite = false
              doFlush()
            } else if (!timers.isTimerActive(Flush))
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

      // an empty result means that all messages were written, as in R2dbcJournal
      promise.future.map(_ => Nil)(ExecutionContext.parasitic)
    }
  }

  private def publish(requests: Vector[(WriteRequest, Instant)]): Unit =
    pubSub.foreach { ps =>
      requests.foreach {
        case (request, timestamp) =>
          try {
            request.messages.foreach { messages =>
              messages.payload.foreach(pr => ps.publish(pr, timestamp))
            }
          } catch {
            case NonFatal(exception) =>
              log.warning(
                "Failed to publish events for persistence id [{}]: [{}]",
                request.messages.head.persistenceId,
                exception.getMessage)
          }
      }
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
    val cause = new IllegalStateException("Journal actor stopped with pending batched writes")

    queue.foreach(_.promise.tryFailure(cause))
    writesInProgress.clear()
    queue.clear()

    super.postStop()
  }

}
