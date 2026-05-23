# Configuration

## Connection configuration

Shared configuration for the connection pool is located under `pekko.persistence.r2dbc.connection-factory`.
You have to set at least:

Postgres:
: @@snip [application.conf](/docs/src/test/resources/application-postgres.conf) { #connection-settings }

Yugabyte:
: @@snip [application.conf](/docs/src/test/resources/application-yugabyte.conf) { #connection-settings }

MySQL:
: @@snip [application.conf](/docs/src/test/resources/application-mysql.conf) { #connection-settings }

## Reference configuration 

The following configuration can be overridden in your `application.conf`:

@@snip [reference.conf](/core/src/main/resources/reference.conf) {#connection-settings}

## Journal configuration

Journal configuration properties are by default defined under `pekko.persistence.r2dbc.journal`.

See @ref:[Journal plugin configuration](journal.md#configuration).

## Snapshot configuration

Snapshot store configuration properties are by default defined under `pekko.persistence.r2dbc.snapshot`.

See @ref:[Snapshot store plugin configuration](snapshots.md#configuration).

## Durable state configuration

Durable state store configuration properties are by default defined under `pekko.persistence.r2dbc.state`.

See @ref:[Durable state plugin configuration](durable-state-store.md#configuration).

## Query configuration

Query configuration properties are by default defined under `pekko.persistence.r2dbc.query`.

See @ref:[Query plugin configuration](query.md#configuration).

## Multiple plugins

To enable the plugins to be used by default, add the following lines to your Pekko `application.conf`:

@@snip [application.conf](/core/src/test/scala/org/apache/pekko/persistence/r2dbc/journal/MultiPluginSpec.scala) {#default-config}

Note that all plugins have a shared root config section `pekko.persistence.r2dbc`, which also contains the
@ref:[Connection configuration](#connection-configuration) for the connection pool that is shared for the plugins.

You can use additional plugins with different configuration. For example if more than one database is used. Then you would define the configuration
such as:

@@snip [application.conf](/core/src/test/scala/org/apache/pekko/persistence/r2dbc/journal/MultiPluginSpec.scala) {#second-config}

To use the additional plugin you would @scala[define]@java[override] the plugin id.

Scala
:  @@snip [MultiPluginDocExample.scala](/core/src/test/scala/org/apache/pekko/persistence/r2dbc/journal/MultiPluginSpec.scala){#withPlugins}

Java
:  @@snip [MultiPluginDocExample.java](/docs/src/test/java/jdocs/home/MultiPluginDocExample.java) {#withPlugins}

It is similar for `DurableStateBehavior`, @scala[define `withDurableStateStorePluginId("second-r2dbc.state")`]
@java[override `durableStateStorePluginId` with `"second-r2dbc.state"`].

For queries and Projection `SourceProvider` you would use `"second-r2dbc.query"` instead of the default @scala[`R2dbcReadJournal.Identifier`]
@java[`R2dbcReadJournal.Identifier()`] (`"pekko.persistence.r2dbc.query"`).

## Plugin configuration at runtime

Plugin implementation supports plugin configuration at runtime.

The following example demonstrates how the database to which the events and snapshots of an `EventSourcedBehavior` are stored can be set during runtime:

@@snip [RuntimePluginConfigExample.scala](/docs/src/test/scala/docs/home/RuntimePluginConfigExample.scala) { #runtime-plugin-config }
