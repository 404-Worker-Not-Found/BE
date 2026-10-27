CREATE TABLE payment_work_terminations (
 work_id BIGINT NOT NULL PRIMARY KEY,
 matching_id BIGINT NOT NULL UNIQUE,
 payment_id BIGINT NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_type VARCHAR(40) NOT NULL,
 result VARCHAR(40) NOT NULL,
 reason VARCHAR(64),
 occurred_at DATETIME(6) NOT NULL,
 processed_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_termination_lock FOREIGN KEY (payment_id) REFERENCES payment_locks(id)
);
CREATE TABLE payment_termination_event_receipts (
 event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 work_id BIGINT NOT NULL,
 matching_id BIGINT NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result VARCHAR(40) NOT NULL,
 received_at DATETIME(6) NOT NULL
);
ALTER TABLE payment_status_histories
 ADD COLUMN termination_work_id BIGINT,
 ADD CONSTRAINT fk_history_termination FOREIGN KEY (termination_work_id) REFERENCES payment_work_terminations(work_id),
 DROP CHECK ck_history_cause,
 ADD CONSTRAINT ck_history_cause CHECK (command_key IS NOT NULL OR settlement_id IS NOT NULL OR termination_work_id IS NOT NULL);
