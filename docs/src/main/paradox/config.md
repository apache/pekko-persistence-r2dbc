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

## Plugin configuration at runtime

Plugin implementation supports plugin configuration at runtime.

The following example demonstrates how the database to which the events and snapshots of an `EventSourcedBehavior` are stored can be set during runtime:

@@snip [RuntimePluginConfigExample.scala](/docs/src/test/scala/docs/home/RuntimePluginConfigExample.scala) { #runtime-plugin-config }
