-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

-- Requires Oracle Database 23ai or later.
-- Oracle stores empty strings and empty byte arrays as NULL, so the entity type, serializer manifest, writer and
-- payload columns are nullable.

CREATE TABLE IF NOT EXISTS event_journal(
  slice NUMBER(10) NOT NULL,
  entity_type VARCHAR2(255),
  persistence_id VARCHAR2(255) NOT NULL,
  seq_nr NUMBER(19) NOT NULL,
  db_timestamp TIMESTAMP(6) NOT NULL,
  event_ser_id NUMBER(10) NOT NULL,
  event_ser_manifest VARCHAR2(255),
  event_payload BLOB,
  deleted BOOLEAN DEFAULT FALSE NOT NULL,
  writer VARCHAR2(255),
  adapter_manifest VARCHAR2(255),
  tags CLOB CHECK (tags IS JSON), -- stored as a JSON array of strings
  meta_ser_id NUMBER(10),
  meta_ser_manifest VARCHAR2(255),
  meta_payload BLOB,
  PRIMARY KEY(persistence_id, seq_nr)
);

CREATE INDEX IF NOT EXISTS event_journal_slice_idx ON event_journal(slice, entity_type, db_timestamp, seq_nr);

CREATE TABLE IF NOT EXISTS snapshot(
  slice NUMBER(10) NOT NULL,
  entity_type VARCHAR2(255),
  persistence_id VARCHAR2(255) NOT NULL,
  seq_nr NUMBER(19) NOT NULL,
  write_timestamp NUMBER(19) NOT NULL,
  ser_id NUMBER(10) NOT NULL,
  ser_manifest VARCHAR2(255),
  snapshot BLOB,
  meta_ser_id NUMBER(10),
  meta_ser_manifest VARCHAR2(255),
  meta_payload BLOB,
  db_timestamp TIMESTAMP(6),
  tags CLOB CHECK (tags IS JSON), -- stored as a JSON array of strings
  PRIMARY KEY(persistence_id)
);

-- `snapshot_slice_idx` is only needed if the slice based queries are used together with snapshot as starting point
CREATE INDEX IF NOT EXISTS snapshot_slice_idx ON snapshot(slice, entity_type, db_timestamp);

CREATE TABLE IF NOT EXISTS durable_state (
  slice NUMBER(10) NOT NULL,
  entity_type VARCHAR2(255),
  persistence_id VARCHAR2(255) NOT NULL,
  revision NUMBER(19) NOT NULL,
  db_timestamp TIMESTAMP(6) NOT NULL,
  state_ser_id NUMBER(10) NOT NULL,
  state_ser_manifest VARCHAR2(255),
  state_payload BLOB,
  tags CLOB CHECK (tags IS JSON), -- stored as a JSON array of strings
  PRIMARY KEY(persistence_id, revision)
);

CREATE INDEX IF NOT EXISTS durable_state_slice_idx ON durable_state(slice, entity_type, db_timestamp, revision);
