# Journal plugin

The journal plugin enables storing and loading events for @extref:[event sourced persistent actors](pekko:typed/persistence.html).

## Schema

The `event_journal` table and `event_journal_slice_idx` index need to be created in the configured database, see schema definition in @ref:[Creating the schema](getting-started.md#schema).

The `event_journal_slice_idx` index is only needed if the slice based @ref:[queries](query.md) are used.

## Relation to Pekko JDBC plugin

Pekko Persistence R2DBC plugin tables are not compatible with the tables of Pekko Persistence JDBC. JDBC data must be migrated using the @ref:[migration tool](migration.md) and a different schema/database must be used (or the table names overridden). 

## Configuration

To enable the journal plugin to be used by default, add the following line to your Pekko `application.conf`:

```
pekko.persistence.journal.plugin = "pekko.persistence.r2dbc.journal"
```

It can also be enabled with the `journalPluginId` for a specific `EventSourcedBehavior` and multiple
plugin configurations are supported.

See also @ref:[Configuration](config.md).

### Reference configuration 

The following can be overridden in your `application.conf` for the journal specific settings:

@@snip [reference.conf](/core/src/main/resources/reference.conf) {#journal-settings}

## Batched Journal

@@@ warning { title="Experimental" }

This feature is experimental and not recommended for production unless it has been thoroughly road tested by the
user in their own test environments.

@@@

The default journal writes each incoming write request with its own statement and commit. The batched journal
plugin (`R2dbcBatchJournal`) instead coalesces concurrent write requests from different persistence ids into one
multi-row insert. This reduces database round trips and commits when many persistence ids write small events at
the same time. It adds latency and changes failure behavior, see @ref:[Tradeoffs](#tradeoffs).

The batched journal requires `use-app-timestamp` and `db-timestamp-monotonic-increasing`, which the
`batched-journal` configuration block enables for this plugin. This is the same timestamp mode that the MySQL
dialect requires. With `db-timestamp-monotonic-increasing` the database does not enforce increasing timestamps per
persistence id, so the application clock must not move backwards between two writes of the same entity. The
backtracking queries of @ref:[eventsBySlices](query.md) recover events that were stored with an out-of-order
timestamp. Batching is only supported for the Postgres and Yugabyte dialects.

### Batched Journal Configuration

To enable the batched journal, point the journal plugin at the `batched-journal` block and update
`application.conf`:

```
pekko.persistence.journal.plugin = "pekko.persistence.r2dbc.batched-journal"

pekko.persistence.r2dbc.batched-journal {
  max-queue-size = 10000 # optional, default value
  max-batch-size = 100 # optional, default value
  max-batch-time = 2ms # optional, default value
}
```

The batched journal uses the following settings, in addition to the settings of the default journal:

- `max-queue-size`: Maximum number of write requests buffered before they are flushed. A write request is
  rejected with a failure when the queue has reached this limit. Must be at least 1. `max-batch-size` must be
  less than or equal to `max-queue-size`.
- `max-batch-size`: Maximum number of write requests in one batch. One request can contain several events when
  the persistent actor uses `persistAll` or `persistAsync`. A batch is flushed when this many requests are
  buffered.
- `max-batch-time`: Maximum time a write request is buffered. If the batch does not reach `max-batch-size`
  first, it is flushed when this duration has elapsed since the first buffered request. Requests that arrive
  while a batch is being written are flushed as soon as that batch completes.

### Tradeoffs

Latency:
A write completes when its batch is flushed, so each write waits up to `max-batch-time`. When `max-batch-size`
requests are buffered the batch is flushed without waiting. Only one batch is written at a time; requests that
arrive while a batch is in flight are flushed as soon as it completes, so under sustained load the journal
batches naturally: batch size follows the number of requests that accumulate during one database round trip
rather than always waiting for `max-batch-time`. This yields smaller batches with lower latency than waiting for
the timer between batches; `max-batch-time` remains the upper bound on how long a request is buffered.

Failures:
Writes of different persistence ids share one database statement. If the database rejects a statement because of
a single persistence id, for example a duplicate sequence number caused by a zombie writer, the batched journal
retries the batch in halves until only the offending write fails. The other persistent actors are not affected.
Failures that are not caused by a single persistence id, for example a lost database connection, fail all writes
of the batch. The affected persistent actors see a journal write failure and are stopped by the default
supervision, as with the default journal. Isolating a single offending write costs about 2·log₂(`max-batch-size`)
additional statements; only when many writes in the batch are offending does the retry approach twice
`max-batch-size` statements, which is the number of statements the default journal would have used for the same
writes.

Memory:
`max-batch-size` limits the number of requests in one batch, not the number of events, and the queue is limited
by `max-queue-size`, which rejects writes once the limit is reached. A single request can contain an arbitrary
number of events when the persistent actor uses `persistAll` or `persistAsync`; neither journal caps that, as in
the default journal. With `persist()` each persistent actor has at most one outstanding write, so the queue grows
with the number of actively writing actors. Buffered writes are held in memory until they are flushed.

When to use:
Batching is most effective when many persistence ids concurrently write small events. With a low number of
concurrent writers, or with large events, the default journal performs better because it adds no buffering
delay.

## Deletes

The journal supports deletes through hard deletes, which means the journal entries are actually deleted from the database. 
There is no materialized view with a copy of the event so make sure to not delete events too early if they are used from projections or queries.

For each persistent id one tombstone record is kept in the event journal when all events of a persistence id have been
deleted. The reason for the tombstone record is to keep track of the latest sequence number so that subsequent events
don't reuse the same sequence numbers that have been deleted.

See the @ref[EventSourcedCleanup tool](cleanup.md#event-sourced-cleanup-tool) for more information about how to delete
events, snapshots and tombstone records.

## Event serialization

The events are serialized with @extref:[Pekko Serialization](pekko:serialization.html) and the binary representation
is stored in the `event_payload` column together with information about what serializer that was used in the
`event_ser_id` and `event_ser_manifest` columns.

For PostgreSQL the payload is stored as `BYTEA` type. Alternatively, you can use `JSONB` column type as described in
@ref:[PostgreSQL JSON](postgres_json.md).
