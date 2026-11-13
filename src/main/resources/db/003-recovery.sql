CREATE TABLE operations (
    id TEXT PRIMARY KEY,
    owner TEXT NOT NULL CHECK(length(owner) BETWEEN 1 AND 64),
    kind TEXT NOT NULL CHECK(kind IN ('ROLLBACK','RESTORE','COMPENSATE')),
    parent TEXT REFERENCES operations(id),
    continuation_of TEXT REFERENCES operations(id),
    session_id INTEGER NOT NULL REFERENCES sessions(id),
    barrier INTEGER NOT NULL,
    boundary INTEGER NOT NULL,
    created INTEGER NOT NULL,
    expires INTEGER NOT NULL,
    scope TEXT NOT NULL CHECK(length(scope)<=2048),
    digest TEXT NOT NULL CHECK(length(digest)=64),
    state TEXT NOT NULL,
    reason TEXT NOT NULL DEFAULT ''
);
CREATE INDEX operations_state ON operations(state);
CREATE TABLE recovery_steps (
    operation TEXT NOT NULL REFERENCES operations(id),
    ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 999),
    source INTEGER NOT NULL REFERENCES changes(id),
    parent_step INTEGER,
    world TEXT NOT NULL,
    x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
    expected TEXT NOT NULL CHECK(length(expected)<=256), desired TEXT NOT NULL CHECK(length(desired)<=256),
    latest INTEGER NOT NULL,
    exclusion TEXT NOT NULL CHECK(length(exclusion)<=256),
    status TEXT NOT NULL DEFAULT 'PLANNED',
    reason TEXT NOT NULL DEFAULT '',
    actual_before TEXT,
    actual_after TEXT,
    audit_id INTEGER UNIQUE REFERENCES changes(id),
    intent_session INTEGER REFERENCES sessions(id),
    verified_session INTEGER REFERENCES sessions(id),
    restored_by TEXT REFERENCES operations(id),
    PRIMARY KEY(operation, ordinal)
);
CREATE INDEX recovery_steps_source ON recovery_steps(source);
CREATE TABLE recovery_effects (
    source INTEGER PRIMARY KEY REFERENCES changes(id),
    rollback_operation TEXT NOT NULL,
    rollback_step INTEGER NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('RESERVED','UNDONE')),
    restore_operation TEXT REFERENCES operations(id),
    restore_step INTEGER,
    FOREIGN KEY(rollback_operation,rollback_step) REFERENCES recovery_steps(operation,ordinal)
);
ALTER TABLE changes ADD COLUMN operation_id TEXT REFERENCES operations(id);
ALTER TABLE changes ADD COLUMN operation_step INTEGER;
CREATE UNIQUE INDEX changes_operation_step ON changes(operation_id,operation_step) WHERE operation_id IS NOT NULL;
