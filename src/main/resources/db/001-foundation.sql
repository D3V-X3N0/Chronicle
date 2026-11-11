CREATE TABLE sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at INTEGER NOT NULL,
    stopped_at INTEGER,
    clean INTEGER NOT NULL DEFAULT 0 CHECK (clean IN (0,1)),
    runtime TEXT NOT NULL,
    last_sequence INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE worlds (uuid TEXT PRIMARY KEY, last_name TEXT NOT NULL);
CREATE TABLE actors (uuid TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK (kind = 'PLAYER'), last_name TEXT NOT NULL);
CREATE TABLE capture_groups (
    session_id INTEGER NOT NULL REFERENCES sessions(id),
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    member_count INTEGER NOT NULL CHECK (member_count > 0),
    PRIMARY KEY (session_id, sequence)
);
CREATE TABLE changes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    capture_sequence INTEGER NOT NULL,
    member_index INTEGER NOT NULL,
    occurred_at INTEGER NOT NULL,
    tick INTEGER NOT NULL,
    world_uuid TEXT NOT NULL REFERENCES worlds(uuid),
    x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
    chunk_x INTEGER NOT NULL, chunk_z INTEGER NOT NULL,
    actor_uuid TEXT NOT NULL REFERENCES actors(uuid),
    actor_name TEXT NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('PLACE','BREAK')),
    before_data TEXT NOT NULL,
    after_data TEXT NOT NULL,
    codec_version INTEGER NOT NULL CHECK (codec_version = 1),
    data_version INTEGER NOT NULL,
    evidence TEXT NOT NULL,
    recovery_eligible INTEGER NOT NULL DEFAULT 0 CHECK (recovery_eligible = 0),
    canonical_payload BLOB NOT NULL,
    UNIQUE (session_id, capture_sequence, member_index),
    FOREIGN KEY (session_id, capture_sequence) REFERENCES capture_groups(session_id, sequence)
);
CREATE INDEX changes_position ON changes(world_uuid, x, y, z, id);
CREATE TABLE coverage_incidents (
    session_id INTEGER NOT NULL REFERENCES sessions(id),
    reason TEXT NOT NULL,
    scope TEXT NOT NULL DEFAULT 'GLOBAL',
    count INTEGER NOT NULL,
    first_time INTEGER NOT NULL, last_time INTEGER NOT NULL,
    first_sequence INTEGER NOT NULL, last_sequence INTEGER NOT NULL,
    PRIMARY KEY (session_id, reason)
);
