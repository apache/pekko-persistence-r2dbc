# Release Notes (1.2.x)

Apache Pekko Persistence R2DBC 1.1.x releases support Java 8 and above.

## 1.2.0

Release notes for Apache Pekko Persistence R2DBC 1.2.0.

### Breaking Changes

* Configuring persistence plugins at runtime ([PR194](https://github.com/apache/pekko-persistence-r2dbc/pull/194))
  * We removed implicit merging of specific plugin configuration (`pekko.persistence.r2dbc.journal`, `pekko.persistence.r2dbc.snapshot`, etc.) with shared configuration (`pekko.persistence.r2dbc`) upon plugin initialization, which means that specific plugin configurations must refer to shared keys explicitly.
  * Users with relatively simple configurations for their applications - ones that more or less adhere to reference configuration structure and are merged with reference configuration (by using, for example, `ConfigFactory.load`) - should be able to migrate without additional changes to configuration.
    * Merging with reference configuration adds explicit shared key references.
