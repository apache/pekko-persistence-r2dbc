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

See also @ref:[Connection configuration](connection-config.md).

### Reference configuration

The following can be overridden in your `application.conf` for the snapshot specific settings:

@@snip [reference.conf](/core/src/main/resources/reference.conf) {#snapshot-settings}

## Usage

The snapshot plugin is used whenever a snapshot write is triggered through the
@extref:[Pekko Persistence APIs](pekko:typed/persistence-snapshot.html).
