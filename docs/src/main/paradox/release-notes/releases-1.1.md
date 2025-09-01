# Release Notes (1.1.x)

Apache Pekko Persistence R2DBC 1.1.x releases support Java 8 and above.

## 1.1.0

Release notes for Apache Pekko Persistence R2DBC 1.1.0. See [GitHub Milestone for 1.1.0-M1](https://github.com/apache/pekko-persistence-r2dbc/milestone/2?closed=1) and [GitHub Milestone for 1.1.0](https://github.com/apache/pekko-persistence-r2dbc/milestone/3?closed=1) for a fuller list of changes.

### Breaking Changes

* The dependency on org.postgresql:r2dbc-postgresql is no longer added to our dependency pom.xml
    * Users need to add their own explicit dependency if they want to use Postgres (version 1.x release recommended)
    * We now support Postgres and MySQL in pekko-persistence-r2dbc and pekko-projection-r2dbc
    * MySQL users will need to add their own explicit dependency on io.asyncer:r2dbc-mysql (latest 1.x release recommended) ([PR175](https://github.com/apache/pekko-persistence-r2dbc/pull/175), [PR177](https://github.com/apache/pekko-persistence-r2dbc/pull/177))
* change R2dbcExecutor functions that work with getRowsUpdated to return Future[Long] ([PR90](https://github.com/apache/pekko-persistence-r2dbc/pull/90))
* Durable State: support revision in deletes ([PR92](https://github.com/apache/pekko-persistence-r2dbc/pull/92))
* Configuring persistence plugins at runtime ([PR194](https://github.com/apache/pekko-persistence-r2dbc/pull/194))
    * We removed implicit merging of specific plugin configuration (`pekko.persistence.r2dbc.journal`, `pekko.persistence.r2dbc.snapshot`, etc.) with shared configuration (`pekko.persistence.r2dbc`) upon plugin initialization, which means that specific plugin configurations must refer to shared keys explicitly.
    * Users with relatively simple configurations for their applications - ones that more or less adhere to reference configuration structure and are merged with reference configuration (by using, for example, `ConfigFactory.load`) - should be able to migrate without additional changes to configuration.
        * Merging with reference configuration adds explicit shared key references.

### Changes

* Add ConnectionFactoryOptionsCustomizer ([PR171](https://github.com/apache/pekko-persistence-r2dbc/pull/171))

### Dependency Changes

* upgrade io.r2dbc dependencies to 1.0.x
* upgraded JDBC driver dependencies used in tests
