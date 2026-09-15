CREATE TABLE chat_rooms (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 matching_id BIGINT NOT NULL,
 job_post_id BIGINT NOT NULL,
 owner_member_id BIGINT NOT NULL,
 worker_member_id BIGINT NOT NULL,
 work_id VARCHAR(255) NOT NULL,
 status VARCHAR(20) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 closed_at DATETIME(6),
 version BIGINT NOT NULL,
 active_matching_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'CLOSED' THEN NULL ELSE matching_id END) STORED,
 UNIQUE KEY uk_chat_active_matching (active_matching_id),
 KEY idx_chat_worker (worker_member_id),
 KEY idx_chat_owner (owner_member_id)
);
CREATE TABLE chat_matching_slots (
 matching_id BIGINT NOT NULL PRIMARY KEY,
 active_chat_room_id BIGINT,
 CONSTRAINT fk_slot_room FOREIGN KEY (active_chat_room_id) REFERENCES chat_rooms(id)
);
CREATE TABLE chat_commands (
 command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 chat_room_id BIGINT,
 CONSTRAINT fk_command_room FOREIGN KEY (chat_room_id) REFERENCES chat_rooms(id)
);
CREATE TABLE chat_status_histories (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 chat_room_id BIGINT NOT NULL,
 previous_status VARCHAR(20),
 next_status VARCHAR(20) NOT NULL,
 command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_history_room FOREIGN KEY (chat_room_id) REFERENCES chat_rooms(id),
 CONSTRAINT fk_history_command FOREIGN KEY (command_key) REFERENCES chat_commands(command_key)
);
