-- Adds the snapshot columns and index that are needed by the queries that use snapshots as starting points
-- (eventsBySlicesStartingFromSnapshots, eventsByPersistenceIdStartingFromSnapshot and their current variants).
-- See the migration guide for a rolling update procedure.

ALTER TABLE snapshot ADD COLUMN db_timestamp TIMESTAMP(6), ADD COLUMN tags JSON;

-- populate the new columns from the corresponding events
UPDATE snapshot s JOIN event_journal e ON s.persistence_id = e.persistence_id AND s.seq_nr = e.seq_nr
  SET s.db_timestamp = e.db_timestamp, s.tags = e.tags;

CREATE INDEX snapshot_slice_idx ON snapshot(slice, entity_type, db_timestamp);
