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

Yugabyte:
: @@snip [create_tables.sql](/ddl-scripts/create_tables_yugabyte.sql)

The ddl script can be run in Docker with:

Postgres:
: ```
docker exec -i docker_postgres-db_1 psql -U postgres -t < ddl-scripts/create_tables_postgres.sql
```

Yugabyte:
: ```
docker exec -i yb-tserver-n1 /home/yugabyte/bin/ysqlsh -h yb-tserver-n1 -t < ddl-scripts/create_tables_yugabyte.sql
```

### Dropping the schema

Postgres:
: @@snip [drop_tables.sql](/ddl-scripts/drop_tables_postgres.sql)

Yugabyte:
: @@snip [drop_tables.sql](/ddl-scripts/drop_tables_postgres.sql)
