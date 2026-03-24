DROP TABLE IF EXISTS locks_data CASCADE;
DROP TABLE IF EXISTS stat_activity CASCADE;
DROP TABLE IF EXISTS snapshots CASCADE;

CREATE TABLE snapshots (
                           id SERIAL PRIMARY KEY,
                           filename VARCHAR(255),
                           upload_time TIMESTAMP DEFAULT NOW(),
                           snapshot_timestamp TIMESTAMP
);

CREATE TABLE stat_activity (
                               id SERIAL PRIMARY KEY,
                               snapshot_id INT REFERENCES snapshots(id) ON DELETE CASCADE,
                               datid BIGINT,
                               datname TEXT,
                               pid INT,
                               usename TEXT,
                               application_name TEXT,
                               client_addr TEXT,
                               backend_start TIMESTAMP,
                               xact_start TIMESTAMP,
                               query_start TIMESTAMP,
                               state_change TIMESTAMP,
                               wait_event_type TEXT,
                               wait_event TEXT,
                               state TEXT,
                               backend_type TEXT,
                               query TEXT
);

CREATE TABLE locks_data (
                            id SERIAL PRIMARY KEY,
                            snapshot_id INT REFERENCES snapshots(id) ON DELETE CASCADE,
                            waiting_pid INT,
                            waiting_mode TEXT,
                            waiting_query TEXT,
                            waiting_duration INTERVAL DAY TO SECOND,
                            locking_pid INT,
                            locking_mode TEXT,
                            locking_query TEXT,
                            locking_duration INTERVAL DAY TO SECOND,
                            wait_event_type TEXT,
                            wait_event TEXT
);

CREATE INDEX idx_stat_snapshot ON stat_activity(snapshot_id);
CREATE INDEX idx_locks_snapshot ON locks_data(snapshot_id);
CREATE INDEX idx_snapshots_time ON snapshots(snapshot_timestamp);
CREATE UNIQUE INDEX idx_unique_snapshot_time ON snapshots(snapshot_timestamp);
CREATE UNIQUE INDEX idx_unique_stat_snapshot_pid ON stat_activity(snapshot_id, pid);
