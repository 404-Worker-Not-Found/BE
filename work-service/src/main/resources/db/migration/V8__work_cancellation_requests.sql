CREATE TABLE work_cancellation_requests (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 work_id BIGINT NOT NULL,
 requester_member_id BIGINT NOT NULL,
 requester_role VARCHAR(20) NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reason VARCHAR(500) NOT NULL,
 status VARCHAR(20) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 decided_at DATETIME(6),
 decision_member_id BIGINT,
 version BIGINT NOT NULL,
 pending_work_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN work_id ELSE NULL END) STORED,
 CONSTRAINT fk_cancellation_work FOREIGN KEY (work_id) REFERENCES works(id),
 CONSTRAINT uk_cancellation_key UNIQUE (work_id, requester_member_id, request_key),
 CONSTRAINT uk_cancellation_pending UNIQUE (pending_work_id),
 INDEX idx_cancellation_work (work_id, id)
);

ALTER TABLE work_outbox ADD INDEX idx_work_outbox_work (work_id);

ALTER TABLE work_outbox
 DROP INDEX uk_work_outbox_revision,
 ADD COLUMN cancellation_request_id BIGINT,
 ADD COLUMN aggregate_type VARCHAR(32) GENERATED ALWAYS AS
   (CASE WHEN cancellation_request_id IS NULL THEN 'WORK' ELSE 'WORK_CANCELLATION' END) STORED,
 ADD COLUMN aggregate_id BIGINT GENERATED ALWAYS AS (COALESCE(cancellation_request_id, work_id)) STORED,
 ADD CONSTRAINT fk_outbox_cancellation FOREIGN KEY (cancellation_request_id) REFERENCES work_cancellation_requests(id),
 ADD CONSTRAINT uk_work_outbox_revision UNIQUE (aggregate_type, aggregate_id, revision);
