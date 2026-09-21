CREATE TABLE payment_deposits (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 job_post_id BIGINT NOT NULL,
 owner_member_id BIGINT NOT NULL,
 currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 deposited_amount DECIMAL(19,2) NOT NULL,
 locked_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
 version BIGINT NOT NULL,
 UNIQUE KEY uk_payment_deposit_job (job_post_id),
 CONSTRAINT ck_deposit_balance CHECK (deposited_amount >= 0 AND locked_amount >= 0 AND locked_amount <= deposited_amount)
);
CREATE TABLE payment_locks (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 deposit_id BIGINT NOT NULL,
 matching_id BIGINT NOT NULL,
 job_post_id BIGINT NOT NULL,
 owner_member_id BIGINT NOT NULL,
 worker_member_id BIGINT NOT NULL,
 amount DECIMAL(19,2) NOT NULL,
 currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(20) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 released_at DATETIME(6),
 version BIGINT NOT NULL,
 active_matching_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'RELEASED' THEN NULL ELSE matching_id END) STORED,
 UNIQUE KEY uk_payment_active_matching (active_matching_id),
 CONSTRAINT fk_payment_deposit FOREIGN KEY (deposit_id) REFERENCES payment_deposits(id),
 CONSTRAINT ck_payment_amount CHECK (amount > 0),
 CONSTRAINT ck_payment_status CHECK (status IN ('LOCKED', 'RELEASED'))
);
CREATE TABLE payment_matching_slots (
 matching_id BIGINT NOT NULL PRIMARY KEY,
 active_payment_id BIGINT,
 CONSTRAINT fk_slot_payment FOREIGN KEY (active_payment_id) REFERENCES payment_locks(id)
);
CREATE TABLE payment_commands (
 command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payment_id BIGINT,
 rejection VARCHAR(50),
 CONSTRAINT fk_command_payment FOREIGN KEY (payment_id) REFERENCES payment_locks(id)
);
CREATE TABLE payment_status_histories (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 payment_id BIGINT NOT NULL,
 previous_status VARCHAR(20),
 next_status VARCHAR(20) NOT NULL,
 command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_history_payment FOREIGN KEY (payment_id) REFERENCES payment_locks(id),
 CONSTRAINT fk_history_command FOREIGN KEY (command_key) REFERENCES payment_commands(command_key)
);
