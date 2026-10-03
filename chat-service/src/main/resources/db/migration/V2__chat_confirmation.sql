ALTER TABLE chat_rooms
    ADD COLUMN confirmed_at DATETIME(6) NULL,
    ADD COLUMN confirmation_revision BIGINT NOT NULL DEFAULT 0,
    ADD INDEX idx_chat_owner_confirmed (owner_member_id, confirmed_at, created_at, id),
    ADD INDEX idx_chat_worker_confirmed (worker_member_id, confirmed_at, created_at, id);

CREATE TABLE chat_confirmation_events (
    event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
