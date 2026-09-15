CREATE TABLE works (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 matching_id BIGINT NOT NULL,
 job_post_id BIGINT NOT NULL,
 owner_member_id BIGINT NOT NULL,
 worker_member_id BIGINT NOT NULL,
 payment_id VARCHAR(255) NOT NULL,
 work_date DATE NOT NULL,
 start_time TIME(6) NOT NULL,
 end_time TIME(6) NOT NULL,
 status VARCHAR(20) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 canceled_at DATETIME(6),
 version BIGINT NOT NULL,
 active_matching_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'CANCELED' THEN NULL ELSE matching_id END) STORED,
 UNIQUE KEY uk_work_active_matching (active_matching_id),
 KEY idx_work_worker_date (worker_member_id, work_date),
 KEY idx_work_owner_date (owner_member_id, work_date)
);
CREATE TABLE work_matching_slots (
 matching_id BIGINT NOT NULL PRIMARY KEY,
 active_work_id BIGINT,
 CONSTRAINT fk_slot_work FOREIGN KEY (active_work_id) REFERENCES works(id)
);
CREATE TABLE work_commands (
 command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 work_id BIGINT,
 CONSTRAINT fk_command_work FOREIGN KEY (work_id) REFERENCES works(id)
);
CREATE TABLE work_status_histories (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 work_id BIGINT NOT NULL,
 previous_status VARCHAR(20),
 next_status VARCHAR(20) NOT NULL,
 command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_history_work FOREIGN KEY (work_id) REFERENCES works(id),
 CONSTRAINT fk_history_command FOREIGN KEY (command_key) REFERENCES work_commands(command_key)
);
