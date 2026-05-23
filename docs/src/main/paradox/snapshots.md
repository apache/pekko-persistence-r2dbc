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

The R2DBC snapshot plugin only ever keeps *one* snapshot per persistence id in the database. 
If a `keepNSnapshots > 1` is specified for an `EventSourcedBehavior` that setting will be ignored.

The reason for this is that there is no real benefit to keep multiple snapshots around on a relational 
database with a high consistency.

See also @ref[EventSourcedCleanup tool](cleanup.md#event-sourced-cleanup-tool).

## Snapshot serialization

The state is serialized with @extref:[Pekko Serialization](pekko:serialization.html) and the binary snapshot representation
is stored in the `snapshot` column together with information about what serializer that was used in the
`ser_id` and `ser_manifest` columns.

For PostgreSQL the payload is stored as `BYTEA` type. Alternatively, you can use `JSONB` column type as described in
@ref:[PostgreSQL JSON](postgres_json.md).
