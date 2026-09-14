ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at DATETIME(6) NULL AFTER retry_count,
    ADD COLUMN lease_token VARCHAR(36) NULL AFTER next_attempt_at,
    ADD COLUMN lease_expires_at DATETIME(6) NULL AFTER lease_token;

UPDATE outbox_events
SET next_attempt_at = occurred_at
WHERE next_attempt_at IS NULL;

ALTER TABLE outbox_events
    MODIFY COLUMN next_attempt_at DATETIME(6) NOT NULL,
    ADD INDEX idx_outbox_events_relay (status, next_attempt_at, lease_expires_at, id);
