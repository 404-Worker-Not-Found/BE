CREATE TABLE notification_device_tokens (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 token VARCHAR(4096) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 member_id BIGINT NOT NULL,
 member_role VARCHAR(20) NOT NULL,
 generation BIGINT NOT NULL DEFAULT 1,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_device_token_hash (token_hash),
 KEY idx_device_recipient (member_id, member_role, active),
 CONSTRAINT ck_device_role CHECK (member_role IN ('OWNER', 'WORKER'))
);
CREATE TABLE notification_push_deliveries (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 notification_id BIGINT NOT NULL,
 device_token_id BIGINT NOT NULL,
 token_generation BIGINT NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 lease_token VARCHAR(36),
 lease_until DATETIME(6),
 next_attempt_at DATETIME(6) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 last_error_code VARCHAR(64),
 CONSTRAINT fk_push_notification FOREIGN KEY (notification_id) REFERENCES notifications(id),
 CONSTRAINT fk_push_device FOREIGN KEY (device_token_id) REFERENCES notification_device_tokens(id),
 UNIQUE KEY uk_push_notification_device (notification_id, device_token_id, token_generation),
 KEY idx_push_pending (state, next_attempt_at, id),
 KEY idx_push_lease (state, lease_until, id),
 CONSTRAINT ck_push_state CHECK (state IN ('PENDING','SENDING','SENT','FAILED','CANCELED'))
);
