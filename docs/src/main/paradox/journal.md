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

## Deletes

The journal supports deletes through hard deletes, which means the journal entries are actually deleted from the database. 
There is no materialized view with a copy of the event so make sure to not delete events too early if they are used from projections or queries.
