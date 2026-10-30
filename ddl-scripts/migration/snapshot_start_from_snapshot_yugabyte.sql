-- Adds the snapshot columns and index that are needed by the queries that use snapshots as starting points
-- (eventsBySlicesStartingFromSnapshots, eventsByPersistenceIdStartingFromSnapshot and their current variants).
-- See the migration guide for a rolling update procedure.

ALTER TABLE snapshot ADD COLUMN IF NOT EXISTS db_timestamp timestamp with time zone;
ALTER TABLE snapshot ADD COLUMN IF NOT EXISTS tags TEXT ARRAY;

-- populate the new columns from the corresponding events
UPDATE snapshot s SET db_timestamp = e.db_timestamp, tags = e.tags
  FROM event_journal e
  WHERE s.persistence_id = e.persistence_id AND s.seq_nr = e.seq_nr AND e.deleted = false;

-- snapshots without a matching event, for example because the events were deleted, get the snapshot's write timestamp,
-- like the snapshot plugin does when it doesn't find the event
UPDATE snapshot SET db_timestamp = TIMESTAMP WITH TIME ZONE 'epoch' + write_timestamp * INTERVAL '1 millisecond'
  WHERE db_timestamp IS NULL;

CREATE INDEX IF NOT EXISTS snapshot_slice_idx ON snapshot(slice ASC, entity_type ASC, db_timestamp ASC, persistence_id)
  SPLIT AT VALUES ((127), (255), (383), (511), (639), (767), (895));
