ALTER TABLE changes ADD COLUMN actor_kind TEXT NOT NULL DEFAULT 'PLAYER';
ALTER TABLE changes ADD COLUMN cause_kind TEXT NOT NULL DEFAULT 'PLAYER_ACTION';
ALTER TABLE changes ADD COLUMN attribution_quality TEXT NOT NULL DEFAULT 'DIRECT';
CREATE TABLE actor_names (
    name TEXT NOT NULL COLLATE NOCASE,
    actor_uuid TEXT NOT NULL REFERENCES actors(uuid),
    PRIMARY KEY (name, actor_uuid)
);
INSERT OR IGNORE INTO actor_names SELECT actor_name, actor_uuid FROM changes;
INSERT OR IGNORE INTO actor_names SELECT last_name, uuid FROM actors;
CREATE INDEX worlds_name ON worlds(last_name COLLATE NOCASE);
CREATE INDEX changes_actor_id ON changes(actor_uuid, id);
CREATE INDEX changes_world_id ON changes(world_uuid, id);
