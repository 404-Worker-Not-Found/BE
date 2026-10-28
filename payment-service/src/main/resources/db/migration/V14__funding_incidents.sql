-- 기존 V12와 #133의 V13 이후 순차 적용한다.
CREATE TABLE payment_funding_incidents (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 funding_revision BIGINT NOT NULL,
 job_post_id BIGINT NOT NULL,
 owner_member_id BIGINT NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 attach_retry_at DATETIME(6),
 UNIQUE KEY uk_funding_incident (order_id, funding_revision),
 KEY idx_funding_incident_job (job_post_id, id),
 CONSTRAINT fk_incident_order FOREIGN KEY (order_id) REFERENCES payment_orders(id)
);
CREATE TABLE payment_funding_incident_deliveries (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 incident_id BIGINT NOT NULL,
 member_id BIGINT NOT NULL,
 member_role VARCHAR(20) NOT NULL,
 matching_id BIGINT,
 work_id BIGINT NOT NULL DEFAULT 0,
 delivered BOOLEAN NOT NULL DEFAULT FALSE,
 lease_token VARCHAR(36),
 lease_until DATETIME(6),
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(40),
 UNIQUE KEY uk_incident_recipient (incident_id, member_id, member_role, work_id),
 KEY idx_incident_delivery_due (delivered, next_attempt_at, lease_until),
 CONSTRAINT fk_incident_delivery FOREIGN KEY (incident_id) REFERENCES payment_funding_incidents(id),
 CONSTRAINT ck_incident_role CHECK (member_role IN ('OWNER','WORKER'))
);

ALTER TABLE payment_settlement_histories ADD COLUMN funding_incident_id BIGINT,
 ADD CONSTRAINT fk_settlement_history_incident FOREIGN KEY (funding_incident_id) REFERENCES payment_funding_incidents(id);

CREATE INDEX idx_settlement_target_incident ON payment_settlement_targets(job_post_id,confirmed_at);
