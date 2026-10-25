# Getting Started

## Dependencies

@@dependency [Maven,sbt,Gradle] {
  group=org.apache.pekko
  artifact=pekko-persistence-r2dbc_$scala.binary.version$
  version=$project.version$
}

This plugin depends on Pekko $pekko.version$ or later, and note that it is important that all `pekko-*` 
dependencies are in the same version, so it is recommended to depend on them explicitly to avoid problems 
with transient dependencies causing an unlucky mix of versions.

The plugin is published for Scala 2.13.

## Enabling

To enable the plugins to be used by default, add the following line to your Pekko `application.conf`:

```
pekko.persistence.journal.plugin = "pekko.persistence.r2dbc.journal"
pekko.persistence.snapshot-store.plugin = "pekko.persistence.r2dbc.snapshot"
pekko.persistence.state.plugin = "pekko.persistence.r2dbc.state"
```

More information in:

* @ref:[journal](journal.md)
* @ref:[snapshot store](snapshots.md)
* @ref:[durable state store](durable-state-store.md)
* @ref:[queries](query.md)

## Local testing

The database can be run in Docker. Here's a sample docker compose file:

Postgres:
: @@snip [docker-compose.yml](/docker/docker-compose-postgres.yml)

Yugabyte:
: @@snip [docker-compose.yml](/docker/docker-compose-yugabyte.yml)

Start with:

Postgres:
: ```
docker compose -f docker/docker-compose-postgres.yml up
```

Yugabyte:
: ```
docker compose -f docker/docker-compose-yugabyte.yml up
```

<a id="schema"></a>
### Creating the schema

Tables and indexes:

Postgres:
: @@snip [create_tables.sql](/ddl-scripts/create_tables_postgres.sql)

Postgres JSONB:
: @@snip [create_tables.sql](/ddl-scripts/create_tables_postgres_jsonb.sql)

Yugabyte:
: @@snip [create_tables.sql](/ddl-scripts/create_tables_yugabyte.sql)

The ddl script can be run in Docker with:

Postgres:
: ```
docker exec -i docker-postgres-db-1 psql -U postgres -t < ddl-scripts/create_tables_postgres.sql
```

Yugabyte:
: ```
docker exec -i yb-tserver-n1 /home/yugabyte/bin/ysqlsh -h yb-tserver-n1 -t < ddl-scripts/create_tables_yugabyte.sql
```

<a id="h2"></a>
### H2

[H2](https://h2database.com) runs in the same JVM process as the application, as an in-memory database or with a
database file. It is useful for tests and small applications that run on a single node. A database file can't be
shared by several JVMs.

The H2 database and its R2DBC driver are not included as dependencies of the plugin, add them to your build:

@@dependency [Maven,sbt,Gradle] {
  group1=com.h2database
  artifact1=h2
  version1=$h2.version$
  group2=io.r2dbc
  artifact2=r2dbc-h2
  version2=$r2dbc-h2.version$
}

Enable it with:

@@snip [application.conf](/docs/src/test/resources/application-h2.conf) { #connection-settings }

Each plugin creates its tables and indexes when it is first used, with the schema and table names of its own
configuration, so no schema scripts are needed. This includes the tables of `state.custom-table` and plugins with a
@ref:[runtime plugin configuration](config.md#plugin-configuration-at-runtime). The tables are not created when the
connection is configured with a `url`.

Additional DDL or DML for the tables of a plugin, for example for the columns of
@ref:[additional columns](durable-state-store.md#storing-query-representation), can be added with `h2.additional-init`
in the configuration of the `journal`, `snapshot` or `state` plugin. It runs once when the plugin starts, after the
plugin has created its tables, and must be idempotent since a file database keeps the tables:

@@snip [H2AdditionalInitForSchemaSpec.scala](/core/src/test/scala/org/apache/pekko/persistence/r2dbc/internal/H2AdditionalInitForSchemaSpec.scala) { #additionalColumn }

The `h2` settings in the reference configuration:

@@snip [reference.conf](/core/src/main/resources/reference.conf) { #connection-settings-h2 }

The H2 dialect stores events, snapshots and durable state in `BYTEA` columns, the `JSONB` payload column type is not
supported. The `connection-factory-options-customizer` is not supported with H2.

### Dropping the schema

Postgres:
: @@snip [drop_tables.sql](/ddl-scripts/drop_tables_postgres.sql)

Yugabyte:
: @@snip [drop_tables.sql](/ddl-scripts/drop_tables_postgres.sql)
