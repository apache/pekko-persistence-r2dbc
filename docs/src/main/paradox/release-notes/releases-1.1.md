# Release Notes (1.1.x)

Apache Pekko Persistence R2DBC 1.1.x releases support Java 8 and above.

## 1.1.0-M2 (not yet released)

Release notes for Apache Pekko Persistence R2DBC 1.1.0-M2.

### Breaking Changes

* Configuring persistence plugins at runtime ([PR194](https://github.com/apache/pekko-persistence-r2dbc/pull/194))
    * We removed implicit merging of specific plugin configuration (`pekko.persistence.r2dbc.journal`, `pekko.persistence.r2dbc.snapshot`, etc.) with shared configuration (`pekko.persistence.r2dbc`) upon plugin initialization, which means that specific plugin configurations must refer to shared keys explicitly.
    * Users with relatively simple configurations for their applications - ones that more or less adhere to reference configuration structure and are merged with reference configuration (by using, for example, `ConfigFactory.load`) - should be able to migrate without additional changes to configuration.
        * Merging with reference configuration adds explicit shared key references.

## 1.1.0-M1

Release notes for Apache Pekko Persistence R2DBC 1.1.0-M1. See [GitHub Milestone for 1.1.0-M1](https://github.com/apache/pekko-persistence-r2dbc/milestone/2?closed=1) for a fuller list of changes.
As with all milestone releases, this release is not recommended for production use - it is designed to allow users to try out the changes in a test environment.

### Breaking Changes

* The dependency on org.postgresql:r2dbc-postgresql is no longer added to our dependency pom.xml
    * Users need to add their own explicit dependency if they want to use Postgres (version 1.0.7.RELEASE recommended)
    * We now support Postgres and MySQL in pekko-persistence-r2dbc and pekko-projection-r2dbc
    * MySQL users will need to add their own explicit dependency on io.asyncer:r2dbc-mysql (version 1.3.0 recommended) ([PR175](https://github.com/apache/pekko-persistence-r2dbc/pull/175), [PR177](https://github.com/apache/pekko-persistence-r2dbc/pull/177))
* change R2dbcExecutor functions that work with getRowsUpdated to return Future[Long] ([PR90](https://github.com/apache/pekko-persistence-r2dbc/pull/90))
* Durable State: support revision in deletes ([PR92](https://github.com/apache/pekko-persistence-r2dbc/pull/92))

### Changes

* Add ConnectionFactoryOptionsCustomizer ([PR171](https://github.com/apache/pekko-persistence-r2dbc/pull/171))

### Dependency Changes

* upgrade io.r2dbc dependencies to 1.0.x
