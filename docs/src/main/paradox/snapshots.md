<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  license agreements; and to You under the Apache License, version 2.0:

    https://www.apache.org/licenses/LICENSE-2.0

  This file is part of the Apache Pekko project, which was derived from Akka.
-->

# Snapshot store plugin

The snapshot plugin enables storing and loading snapshots for @extref:[event sourced persistent actors](pekko:typed/persistence.html).

## Schema

The `snapshot` table need to be created in the configured database, see schema definition in @ref:[Creating the schema](getting-started.md#schema).

## Configuration

To enable the snapshot plugin to be used by default, add the following line to your Pekko `application.conf`:

```
pekko.persistence.snapshot-store.plugin = "pekko.persistence.r2dbc.snapshot"
```

It can also be enabled with the `snapshotPluginId` for a specific `EventSourcedBehavior` and multiple
plugin configurations are supported.

See also @ref:[Configuration](config.md).

### Reference configuration

The following can be overridden in your `application.conf` for the snapshot specific settings:

@@snip [reference.conf](/core/src/main/resources/reference.conf) {#snapshot-settings}

## Usage

The snapshot plugin is used whenever a snapshot write is triggered through the
@extref:[Pekko Persistence APIs](pekko:typed/persistence-snapshot.html).

## Retention

The R2DBC snapshot store keeps one snapshot per persistence ID. Saving another snapshot replaces the
previous one. The `keepNSnapshots` argument to typed persistence retention cannot make this store
keep additional snapshots, but it can affect which **journal events** retention deletes.

The plugin inherits `only-one-snapshot` from Pekko Core's `snapshot-store-plugin-fallback` unless you
set it for this plugin. The standard fallback is `false`. With `false` and
`withDeleteEventsOnSnapshot`, `keepNSnapshots` determines how many batches of journal events remain.
Typed persistence can still send requests to delete old snapshots, even though this store has already
replaced them.

To opt in to single-snapshot retention for the default R2DBC snapshot plugin, add this to
`application.conf` (use your configured snapshot plugin path instead if you use a custom plugin ID):

```hocon
pekko.persistence.r2dbc.snapshot.only-one-snapshot = true
```

With this setting, typed persistence skips deletion requests for old snapshots, avoiding a database
round trip when retention would otherwise send one. With `keepNSnapshots > 1`, typed persistence
logs a warning; use `RetentionCriteria.snapshotEvery(numberOfEvents)` instead.

If your retention criteria also enable `withDeleteEventsOnSnapshot`, typed persistence can delete
journal events up to and including the latest successfully saved retention snapshot's sequence
number, rather than keeping the event history associated with `keepNSnapshots`. Enable the setting
only if consumers that need those older events can tolerate their deletion. This setting does not
enable event deletion by itself, change what the snapshot store saves, or change its schema.

See also @ref[EventSourcedCleanup tool](cleanup.md#event-sourced-cleanup-tool).

## Snapshots as starting points for queries

When `pekko.persistence.r2dbc.query.start-from-snapshot.enabled` is on, the snapshot plugin also stores the
timestamp and tags of the corresponding event in the `db_timestamp` and `tags` columns. Those are used by the
@ref:[queries that use snapshots as starting points](query.md#eventsbyslicesstartingfromsnapshots). See the
@ref:[migration guide](migration-guides.md#eventsBySlicesStartingFromSnapshots) for the schema changes.

The defaults of these snapshot plugin settings refer to the default plugin config paths:

* `start-from-snapshot.enabled` defaults to `pekko.persistence.r2dbc.query.start-from-snapshot.enabled`
* `journal-table` defaults to `pekko.persistence.r2dbc.journal.table`, and is used with the `schema` of the snapshot plugin

If you use custom plugin ids, or the journal table is in another schema than the snapshot table, set them in the
snapshot plugin config. Otherwise the corresponding event is not found, and the snapshot is stored with its write
timestamp and without tags. A warning is logged when that happens.

## Snapshot serialization

The state is serialized with @extref:[Pekko Serialization](pekko:serialization.html) and the binary snapshot representation
is stored in the `snapshot` column together with information about what serializer that was used in the
`ser_id` and `ser_manifest` columns.

For PostgreSQL the payload is stored as `BYTEA` type. Alternatively, you can use `JSONB` column type as described in
@ref:[PostgreSQL JSON](postgres_json.md).
