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
import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration.{ Duration, FiniteDuration, NANOSECONDS }
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.jdk.DurationConverters.JavaDurationOps
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import io.r2dbc.spi.R2dbcException
import io.r2dbc.spi.R2dbcNonTransientResourceException

import org.apache.pekko
import pekko.Done
import pekko.actor.Scheduler
import pekko.actor.Timers
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.adapter._
import pekko.annotation.InternalApi
import pekko.event.Logging
import pekko.pattern.after
import pekko.persistence.AtomicWrite
import pekko.persistence.Persistence
import pekko.persistence.PersistentRepr
import pekko.persistence.journal.AsyncWriteJournal
import pekko.persistence.journal.Tagged
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

import com.typesafe.config.Config

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

  private[r2dbc] def nextTimestamp(): Instant = InstantFactory.nowStrictlyIncreasing()

  // r2dbc-mysql reports some data exceptions without a SQLSTATE, for example error 1406 (data too long) from a
  // batched insert, as an R2dbcNonTransientResourceException with getSqlState == null and the server error code
  private final val MySqlDataExceptionErrorCodes = Set(1406, 3140)

  private[r2dbc] def isNonTransientStatementError(exception: Throwable): Boolean =
    exception match {
      case _: R2dbcDataIntegrityViolationException                        => true
      case e: R2dbcNonTransientResourceException if e.getSqlState == null =>
        MySqlDataExceptionErrorCodes.contains(e.getErrorCode)
      case e: R2dbcException =>
        e.getSqlState != null && (e.getSqlState.startsWith("22") || e.getSqlState.startsWith("23"))
      case _ => false
    }

  // Errors that roll back the transaction because it conflicted with a concurrent transaction, so the same writes can
  // succeed when they are retried: serialization failure or deadlock (SQLSTATE 40001, PostgreSQL deadlock 40P01).
  // MySQL and MariaDB deadlocks are matched by error code (1213) too, as r2dbc-mysql doesn't always report a SQLSTATE.
  // Lock-wait timeouts (MySQL/MariaDB 1205, H2 HYT00, PostgreSQL 55P03) are not retried: they arrive only after the
  // full lock timeout, the lock is usually still held, and as only one flush is in flight each retry would delay all
  // queued writes of the journal by another lock timeout.
  private final val TransientConflictSqlStates = Set("40001", "40P01")
  private final val MySqlTransientConflictErrorCodes = Set(1213)

  private[r2dbc] def isTransientConflictError(exception: Throwable): Boolean =
    exception match {
      case e: R2dbcException =>
        (e.getSqlState != null && TransientConflictSqlStates.contains(e.getSqlState)) ||
        MySqlTransientConflictErrorCodes.contains(e.getErrorCode)
      case _ => false
    }

  /**
   * Runs `attempt`, and runs it again up to `retries` times when it fails with a transient conflict error. The n-th
   * retry is delayed by n times `backoff` plus a random part of up to `backoff`, so that conflicting writers don't retry
   * in lockstep.
   */
  private[r2dbc] def retryTransientConflicts[T](retries: Int, backoff: FiniteDuration, scheduler: Scheduler)(
      onRetry: (Throwable, Int) => Unit)(attempt: () => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    def run(retry: Int): Future[T] =
      attempt().recoverWith {
        case exception if retry <= retries && isTransientConflictError(exception) =>
          onRetry(exception, retry)
          val jitter = if (backoff > Duration.Zero) ThreadLocalRandom.current().nextLong(backoff.toNanos) else 0L
          after(backoff * retry.toLong + FiniteDuration(jitter, NANOSECONDS), scheduler)(run(retry + 1))
      }
    run(1)
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
 * microseconds and bumped to stay strictly increasing across all journal actor instances in the JVM.
 * Equal `db_timestamp` values therefore never span more than one write request within the JVM, so
 * the `eventsBySlices` query can page through any batch regardless of its buffer size. The
 * stamps can lead the wall clock by at most `max-batch-size` microseconds per flush. A single
 * request can still contain many events when the caller uses `persistAll` or `persistAsync`
 * bursts, the same as the default journal.
 *
 * A batch that fails with a transient conflict, a deadlock or serialization failure, is retried
 * as a whole up to `transient-error-retries` times with a growing, randomized delay, re-stamping
 * its requests with new, later timestamps. Only one flush is in flight, so the retry keeps
 * same-persistence-id writes in order, and the retries delay all queued writes. Lock-wait
 * timeouts are not retried.
 *
 * A batch that fails with a data integrity violation or a per-row data exception, for example a
 * value that violates a dialect payload limit, is retried in halves so that only the offending
 * persistence ids fail. Infrastructure errors, and conflicts that persist after the retries, fail
 * the whole batch. The retried halves are written concurrently and can use several pool
 * connections at once. Each half re-stamps its requests with new, later timestamps. A single split
 * preserves order, but if the half holding the earlier sequence numbers is retried after its
 * sibling committed, its re-stamped rows can invert the `db_timestamp` order of two
 * same-persistence-id writes. Replay still orders by sequence number; only timestamp-ordered read
 * sides see the inversion. This plugin targets many small concurrent writes.
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

  private val transientErrorRetries: Int = config.getInt("transient-error-retries")
  private val transientErrorRetryBackoff: FiniteDuration = config.getDuration("transient-error-retry-backoff").toScala

  require(transientErrorRetries >= 0, "transient-error-retries must not be negative when using R2dbcBatchJournal")
  require(transientErrorRetryBackoff >= Duration.Zero,
    "transient-error-retry-backoff must not be negative when using R2dbcBatchJournal")

  // the actor context must not be used from the Future callbacks that schedule the retries
  private val scheduler: Scheduler = context.system.scheduler

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

    // each attempt re-stamps the requests, so that a retried batch gets new, later timestamps
    def writeOnce(requests: Vector[WriteRequest]): Future[Unit] = {
      val stampedRequests = requests.map(request => request -> R2dbcBatchJournal.nextTimestamp())

      journalDao
        .writeEvents(stampedRequests.flatMap {
          case (request, timestamp) => request.rows.map(_.copy(dbTimestamp = timestamp))
        })
        .map { _ =>
          requests.foreach(_.promise.trySuccess(Done))
          publish(stampedRequests)
        }
    }

    def write(requests: Vector[WriteRequest]): Future[Unit] =
      R2dbcBatchJournal
        .retryTransientConflicts(transientErrorRetries, transientErrorRetryBackoff, scheduler) { (exception, retry) =>
          log.info("retry [{}] of [{}] write requests after a transient conflict: {}", retry, requests.size, exception)
        } { () =>
          writeOnce(requests)
        }
        .recoverWith {
          case exception if requests.size > 1 && R2dbcBatchJournal.isNonTransientStatementError(exception) =>
            val (left, right) = requests.splitAt(requests.size / 2)
            write(left).zipWith(write(right))((_, _) => ())
          case exception =>
            requests.foreach(_.promise.tryFailure(exception))
            Future.unit
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
    case FlushDone(g) =>
      if (g == generation) {
        noActiveWrite = true
        if (queue.size >= maxBatchSize) {
          noActiveWrite = false
          doFlush()
        } else if (queue.nonEmpty && !timers.isTimerActive(Flush)) {
          timers.startSingleTimer(Flush, Flush, maxBatchTime)
        }
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
                exception,
                "Failed to publish events for persistence id [{}]",
                request.messages.head.persistenceId)
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
