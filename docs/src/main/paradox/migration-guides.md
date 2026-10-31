# Migration Guides

Apache Pekko Persistence R2DBC 1.0.0 is based on Akka Persistence R2DBC 0.7.7.

## MySQL time zone in 2.0.0

Since 1.1.0 the plugin set the `connectionTimeZone` option of the MySQL driver to `SERVER`, so that the driver converted
timestamps with the time zone of the server. Since 2.0.0 it sets `connectionTimeZone` to `UTC` and
`forceConnectionTimeZoneToSession` to `true`, so that the database session uses UTC, see
@ref:[MySQL](getting-started.md#mysql).

SQL that runs on the plugin's connections, for example in a `ChangeHandler` of durable state, now sees `NOW()`,
`CURRENT_TIMESTAMP` and `TIMESTAMP` values in UTC instead of in the time zone of the server.

### Timestamps stored by earlier versions

If the driver could resolve the time zone of the server, rows written by earlier versions are read as the same instants
after upgrading. Only timestamps in the local hour that is repeated when daylight saving time ends may have been stored
one hour off.

If the driver couldn't resolve the time zone of the server, it used the time zone of the JVM instead and logged a
warning:

```
The server timezone is unknown <EDT>, trying to use system default timezone
```

This happens for abbreviations such as `EDT`, `CDT`, `PDT` or `AEST` in `@@system_time_zone`. If the time zone of the
JVM is also different from the time zone of the server, all timestamps written by earlier versions are stored shifted by
the difference between the two time zones, and are read shifted after upgrading. You can check it by comparing the
latest timestamps with the current time in a UTC session, on a system that is writing events:

```sql
SET time_zone = '+00:00';
SELECT MAX(db_timestamp), UTC_TIMESTAMP(6) FROM event_journal;
```

If they differ by the difference between the time zones of the JVM and the server, the stored timestamps are shifted,
in either direction:

* When the time zone of the JVM is ahead of the time zone of the server, for example UTC and `America/New_York`, the
  stored timestamps and the timestamp offsets of projections are in the future after upgrading. Events written after
  the upgrade are then older than the offsets, and `eventsBySlices` queries and projections can skip them, which
  backtracking doesn't cover.
* When the time zone of the JVM is behind the time zone of the server, for example `America/Los_Angeles` and
  `America/New_York`, the stored timestamps are in the past, which can look like a system that hasn't written events
  for a while.

In both cases, correct the stored timestamps before upgrading. Stop all applications that write to or project from the
tables, and run the following in a UTC session. Replace `<jvm-time-zone>` with the time zone of the JVM, for example
`America/Los_Angeles`, or `+00:00` if the JVM runs in UTC, and use the table names and schema that you have
configured:

```sql
SET time_zone = '+00:00';
UPDATE event_journal
  SET db_timestamp = CONVERT_TZ(CONVERT_TZ(db_timestamp, '+00:00', 'SYSTEM'), '<jvm-time-zone>', '+00:00');
UPDATE durable_state
  SET db_timestamp = CONVERT_TZ(CONVERT_TZ(db_timestamp, '+00:00', 'SYSTEM'), '<jvm-time-zone>', '+00:00');
-- the timestamp offsets of Apache Pekko Projection R2DBC
UPDATE projection_timestamp_offset_store
  SET timestamp_offset = CONVERT_TZ(CONVERT_TZ(timestamp_offset, '+00:00', 'SYSTEM'), '<jvm-time-zone>', '+00:00'),
    timestamp_consumed = CONVERT_TZ(CONVERT_TZ(timestamp_consumed, '+00:00', 'SYSTEM'), '<jvm-time-zone>', '+00:00');
```

Named time zones such as `America/Los_Angeles` require the time zone tables of MySQL to be loaded. Timestamps in the
local hour of the JVM time zone that is repeated when daylight saving time ends can't be corrected exactly. All
applications must have run in the same time zone.

Correct the `event_journal` table before running `snapshot_start_from_snapshot_mysql.sql`, see
@ref:[Snapshots as starting points for queries](#eventsBySlicesStartingFromSnapshots), so that it copies the corrected
timestamps of the events to the `snapshot` table. If the `snapshot` table already has the `db_timestamp` column, set it
again from the corrected events instead of converting it, because the timestamps of snapshots without an event are
taken from `write_timestamp` and are not shifted:

```sql
SET time_zone = '+00:00';
UPDATE snapshot s JOIN event_journal e
  ON s.persistence_id = e.persistence_id AND s.seq_nr = e.seq_nr AND e.deleted = false
  SET s.db_timestamp = e.db_timestamp;
UPDATE snapshot s SET s.db_timestamp = FROM_UNIXTIME(s.write_timestamp / 1000)
  WHERE NOT EXISTS (SELECT 1 FROM event_journal e
    WHERE e.persistence_id = s.persistence_id AND e.seq_nr = s.seq_nr AND e.deleted = false);
```

Alternatively, keep the previous options until the data is corrected, with
`connectionTimeZone=SERVER&forceConnectionTimeZoneToSession=false` in the `url`, or with a
`connection-factory-options-customizer`. The previous problems remain then:

```scala
import com.typesafe.config.Config
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Option
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.r2dbc.ConnectionFactoryProvider.ConnectionFactoryOptionsCustomizer

class PreviousMySQLTimeZone(system: ActorSystem[_]) extends ConnectionFactoryOptionsCustomizer {
  override def apply(builder: ConnectionFactoryOptions.Builder, config: Config): ConnectionFactoryOptions.Builder =
    builder
      .option(Option.valueOf[String]("connectionTimeZone"), "SERVER")
      .option(Option.valueOf[java.lang.Boolean]("forceConnectionTimeZoneToSession"), java.lang.Boolean.FALSE)
}
```

```HOCON
pekko.persistence.r2dbc.connection-factory.connection-factory-options-customizer = "com.example.PreviousMySQLTimeZone"
```

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
