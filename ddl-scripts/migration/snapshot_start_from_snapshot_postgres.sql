-- Adds the snapshot columns and index that are needed by the queries that use snapshots as starting points
-- (eventsBySlicesStartingFromSnapshots, eventsByPersistenceIdStartingFromSnapshot and their current variants).
-- Use this for both the BYTEA and the JSONB schema. See the migration guide for a rolling update procedure.

ALTER TABLE snapshot ADD COLUMN IF NOT EXISTS db_timestamp timestamp with time zone;
ALTER TABLE snapshot ADD COLUMN IF NOT EXISTS tags TEXT ARRAY;

-- populate the new columns from the corresponding events
UPDATE snapshot s SET db_timestamp = e.db_timestamp, tags = e.tags
  FROM event_journal e
  WHERE s.persistence_id = e.persistence_id AND s.seq_nr = e.seq_nr AND e.deleted = false;

CREATE INDEX IF NOT EXISTS snapshot_slice_idx ON snapshot(slice, entity_type, db_timestamp);
