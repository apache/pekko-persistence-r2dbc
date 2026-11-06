-- Adds the snapshot columns and index that are needed by the queries that use snapshots as starting points
-- (eventsBySlicesStartingFromSnapshots, eventsByPersistenceIdStartingFromSnapshot and their current variants).
-- See the migration guide for a rolling update procedure.

ALTER TABLE snapshot ADD (db_timestamp TIMESTAMP(6), tags CLOB CHECK (tags IS JSON));

MERGE INTO snapshot s
  USING (SELECT persistence_id, seq_nr, db_timestamp, tags FROM event_journal WHERE deleted = false) e
  ON (s.persistence_id = e.persistence_id AND s.seq_nr = e.seq_nr)
  WHEN MATCHED THEN UPDATE SET s.db_timestamp = e.db_timestamp, s.tags = e.tags;

UPDATE snapshot SET db_timestamp = TIMESTAMP '1970-01-01 00:00:00' + NUMTODSINTERVAL(write_timestamp / 1000, 'SECOND')
  WHERE db_timestamp IS NULL;

CREATE INDEX IF NOT EXISTS snapshot_slice_idx ON snapshot(slice, entity_type, db_timestamp);
