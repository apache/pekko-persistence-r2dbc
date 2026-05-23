# PostgreSQL JSON

By default, the serialized event, snapshot and durable state payloads, are stored in `BYTEA` columns. Alternatively,
you can use `JSONB` column type to take advantage of PostgreSQL support for [JSON Types](https://www.postgresql.org/docs/current/datatype-json.html).
For example, then you can add secondary jsonb indexes on the payload content for queries.

To enable `JSONB` payloads you need the following.

1. Create the schema as shown in the Postgres JSONB tab in @ref:[Creating the schema](getting-started.md#schema).

1. Define configuration:
    ```
    pekko.persistence.r2dbc {
      journal.payload-column-type = JSONB
      snapshot.payload-column-type = JSONB
      state.payload-column-type = JSONB
    }
    ```

1. Serialize the event, snapshot and durable state payloads as JSON bytes.

For the serialization you can use:
* @extref:[Pekko Serialization with Jackson](pekko:serialization-jackson.html) with JSON format.
  * Make sure to disable @extref:[compression](pekko:serialization-jackson.html#compression) with `pekko.serialization.jackson.jackson-json.compression.algorithm = off`
* Plain strings in JSON format.
* A custom Pekko serializer that uses a binary format as UTF-8 encoded JSON string.

Note that you can enable this feature selectively for the event journal, snapshot, and durable state.
