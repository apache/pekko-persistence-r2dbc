# Migration Guides

Apache Pekko Persistence R2DBC 1.0.0 is based on Akka Persistence R2DBC 0.7.7.

## Migration to Apache Pekko

These migration notes are designed for users migrating from Akka Persistence R2DBC 0.7.7 to Persistence R2DBC 1.0
and assume a basic level of Akka experience. Please feel free to submit an issue or a patch if you feel like the notes can be improved.

One of the main differences is that the database tables used by `pekko-projection-r2dbc` have been renamed to
remove the `akka` prefixes ([PR71](https://github.com/apache/pekko-persistence-r2dbc/pull/71)).

The table names that `pekko-projection-r2dbc` expects to find can be changed using [configuration settngs](https://github.com/lightbend/config).

Users migrating from Akka who want to reuse the pre-existing tables could set a config like:

```HOCON
pekko.projection.r2dbc.offset-store {
    offset-table = "akka_projection_offset_store"
    timestamp-offset-table = "akka_projection_timestamp_offset_store"
    management-table = "akka_projection_management"
}
```

<a id="eventsBySlicesStartingFromSnapshots"></a>

## Snapshots as starting points for queries

The queries that use snapshots as starting points are new in Apache Pekko Persistence R2DBC 2.0.0, which requires
Apache Pekko 2.0.0. The query interfaces they implement are defined in Pekko Persistence Query 2.0.0.

These changes are only needed if you use the @ref:[queries that use snapshots as starting points](query.md#eventsbyslicesstartingfromsnapshots):
`eventsBySlicesStartingFromSnapshots`, `currentEventsBySlicesStartingFromSnapshots`,
`eventsByPersistenceIdStartingFromSnapshot` and `currentEventsByPersistenceIdStartingFromSnapshot`.

The `snapshot` table needs two new columns, `db_timestamp` and `tags`, populated from the corresponding events in the
`event_journal` table, and a new index. The `ddl-scripts/migration` directory contains a script per database that does
all three steps:

* PostgreSQL (both the `BYTEA` and the `JSONB` schema): `snapshot_start_from_snapshot_postgres.sql`
* Yugabyte: `snapshot_start_from_snapshot_yugabyte.sql`
* MySQL and MariaDB: `snapshot_start_from_snapshot_mysql.sql`

For example, for PostgreSQL:

```sql
ALTER TABLE snapshot ADD COLUMN IF NOT EXISTS db_timestamp timestamp with time zone;
ALTER TABLE snapshot ADD COLUMN IF NOT EXISTS tags TEXT ARRAY;

UPDATE snapshot s SET db_timestamp = e.db_timestamp, tags = e.tags
  FROM event_journal e
  WHERE s.persistence_id = e.persistence_id AND s.seq_nr = e.seq_nr AND e.deleted = false;

UPDATE snapshot SET db_timestamp = TIMESTAMP WITH TIME ZONE 'epoch' + write_timestamp * INTERVAL '1 millisecond'
  WHERE db_timestamp IS NULL;

CREATE INDEX IF NOT EXISTS snapshot_slice_idx ON snapshot(slice, entity_type, db_timestamp);
```

The first update only uses events that are not marked as deleted, like the snapshot plugin does. Snapshots without
such an event, for example because the events were deleted with `deleteMessages`, get the snapshot's write timestamp
and no tags from the second update, also like the snapshot plugin does when it doesn't find the event. Without a
timestamp, such snapshots would not be emitted by the queries that start from snapshots. For a large `snapshot`
table you may want to run the updates in batches, for example by restricting them to a range of `slice` values per
statement, to avoid one long running transaction.

The new schema in `ddl-scripts/create_tables_*.sql` already includes the columns and the index. With H2, the plugin
creates the snapshot table with the columns, and creates the index when the feature is enabled.

The feature must be enabled with configuration:

```hocon
pekko.persistence.r2dbc.query.start-from-snapshot.enabled = true
```

The snapshot plugin reads `pekko.persistence.r2dbc.snapshot.start-from-snapshot.enabled`, which defaults to the
query setting. When enabled, the snapshot plugin stores the timestamp and tags of the corresponding event with each
snapshot. The @ref:[migration tool](migration.md) does the same for migrated snapshots when the events have been
migrated first.

These changes can be made in a rolling update process:

1. While running the old version, alter the `snapshot` table to add the two columns.
2. Enable the configuration and roll out the new version. Don't use the queries that start from snapshots yet.
3. Populate the columns with the update statement. Create the index.
4. Roll out another version where you can use the queries that start from snapshots.

As long as `start-from-snapshot.enabled` is off the snapshot plugin doesn't use the new columns, so the schema change
is not needed if you don't use these queries.
