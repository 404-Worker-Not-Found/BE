CREATE TABLE notification_events (
 event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
);
CREATE TABLE notifications (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 member_id BIGINT NOT NULL,
 member_role VARCHAR(20) NOT NULL,
 notification_type VARCHAR(32) NOT NULL,
 job_post_id BIGINT NOT NULL,
 matching_id BIGINT,
 application_id BIGINT NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 read_at DATETIME(6),
 CONSTRAINT fk_notification_event FOREIGN KEY (event_id) REFERENCES notification_events(event_id),
 UNIQUE KEY uk_notification_recipient (event_id, member_id, member_role),
 KEY idx_notification_member_id (member_id, member_role, id),
 KEY idx_notification_unread (member_id, member_role, read_at)
);
