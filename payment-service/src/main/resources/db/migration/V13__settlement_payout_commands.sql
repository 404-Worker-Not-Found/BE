-- 현재 develop에서 이미 적용 가능한 V12 이후에 지급 스키마를 추가한다.
CREATE TABLE payment_payout_commands (
 settlement_id BIGINT NOT NULL PRIMARY KEY,
 reference VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recipient VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 amount DECIMAL(19,2) NOT NULL,
 currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(20) NOT NULL,
 provider_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin,
 reason VARCHAR(40),
 claim_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 lease_until DATETIME(6),
 next_attempt_at DATETIME(6) NOT NULL,
 attempt INT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_payout_reference (reference),
 UNIQUE KEY uk_payout_provider (provider_id),
 KEY idx_payout_due (status, next_attempt_at, lease_until),
 CONSTRAINT fk_payout_settlement FOREIGN KEY (settlement_id) REFERENCES payment_settlements(id),
 CONSTRAINT ck_payout_amount CHECK (amount > 0 AND amount = FLOOR(amount)),
 CONSTRAINT ck_payout_currency CHECK (currency = 'KRW'),
 CONSTRAINT ck_payout_status CHECK (status IN ('AWAITING_RESULT', 'SUCCEEDED', 'FAILED', 'REVIEW_REQUIRED'))
);

ALTER TABLE payment_settlements
 ADD COLUMN payout_next_check_at DATETIME(6),
 ADD COLUMN payout_check_failures INT NOT NULL DEFAULT 0,
 ADD COLUMN payout_check_reason VARCHAR(40),
 ADD INDEX idx_settlement_payout_check (status,payout_next_check_at);
