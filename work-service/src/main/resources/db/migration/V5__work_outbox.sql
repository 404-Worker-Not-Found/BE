CREATE TABLE work_outbox (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 work_id BIGINT NOT NULL,
 event_type VARCHAR(40) NOT NULL,
 revision BIGINT NOT NULL,
 schema_version INT NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 payload JSON NOT NULL,
 next_attempt_at DATETIME(6) NOT NULL,
 retry_count INT NOT NULL DEFAULT 0,
 lease_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 lease_until DATETIME(6),
 published_at DATETIME(6),
 CONSTRAINT uk_work_outbox_event UNIQUE (event_id),
 CONSTRAINT uk_work_outbox_revision UNIQUE (work_id, revision),
 CONSTRAINT fk_work_outbox_work FOREIGN KEY (work_id) REFERENCES works(id),
 INDEX idx_work_outbox_pending (published_at, next_attempt_at, id)
);
