ALTER TABLE payment_deposits ADD COLUMN funding_blocked BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE payment_orders (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 job_post_id BIGINT NOT NULL,
 job_version BIGINT NOT NULL,
 owner_member_id BIGINT NOT NULL,
 amount DECIMAL(19,2) NOT NULL,
 currency VARCHAR(3) NOT NULL,
 status VARCHAR(24) NOT NULL,
 payment_key VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin UNIQUE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 approved_at DATETIME(6),
 next_check_at DATETIME(6),
 reconcile_revision BIGINT NOT NULL DEFAULT 0,
 lease_token VARCHAR(36),
 lease_until DATETIME(6),
 attempts INT NOT NULL DEFAULT 0,
 CONSTRAINT ck_order_amount CHECK (amount >= 100 AND amount = FLOOR(amount)),
 CONSTRAINT ck_order_currency CHECK (currency = 'KRW'),
 CONSTRAINT ck_order_status CHECK (status IN ('READY','CONFIRMING','DEPOSITED','FAILED','SUPERSEDED','REVIEW_REQUIRED')),
 KEY idx_order_recovery (next_check_at, lease_until)
);
CREATE TABLE payment_order_jobs (
 job_post_id BIGINT PRIMARY KEY,
 active_order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
 CONSTRAINT fk_order_job_order FOREIGN KEY (active_order_id) REFERENCES payment_orders(id)
);
CREATE TABLE payment_order_commands (
 command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 fingerprint VARCHAR(200) NOT NULL,
 order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
 CONSTRAINT fk_order_command_order FOREIGN KEY (order_id) REFERENCES payment_orders(id)
);
CREATE TABLE payment_order_histories (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(24) NOT NULL,
 occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_order_history_order FOREIGN KEY (order_id) REFERENCES payment_orders(id)
);
CREATE TABLE payment_funding_notifications (
 order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 revision BIGINT NOT NULL,
 funded BOOLEAN NOT NULL,
 delivered BOOLEAN NOT NULL DEFAULT FALSE,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 lease_token VARCHAR(36),
 lease_until DATETIME(6),
 attempts INT NOT NULL DEFAULT 0,
 CONSTRAINT fk_notification_order FOREIGN KEY (order_id) REFERENCES payment_orders(id),
 KEY idx_funding_delivery (delivered, next_attempt_at, lease_until)
);
