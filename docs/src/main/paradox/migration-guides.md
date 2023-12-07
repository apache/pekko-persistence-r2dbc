# Migration Guides

Apache Pekko Persistence R2DBC 1.0.0 is based on Akka Persistence R2DBC 0.7.7.

## Migration to Apache Pekko

These migration notes are designed for users migrating from Akka Persistence R2DBC 0.7.7 to Persistence R2DBC 1.0
and assume a basic level of Akka experience. Please feel free to submit an issue or a patch if you feel like the notes can be improved.

One of the main differences is that the database tables used by `pekko-projection-r2dbc` have been renamed to
remove the `akka` prefixes ([PR71](https://github.com/apache/incubator-pekko-persistence-r2dbc/pull/71)).

The table names that `pekko-projection-r2dbc` expects to find can be changed using [configuration settngs](https://github.com/lightbend/config).

Users migrating from Akka who want to reuse the pre-existing tables could set a config like:

```HOCON
pekko.projection.r2dbc.offset-store {
    offset-table = "akka_projection_offset_store"
    timestamp-offset-table = "akka_projection_timestamp_offset_store"
    management-table = "akka_projection_management"
}
```
