-- 정산 기반 모델. 기존 잠금·명령·이력은 그대로 두고, 근무 완료를 확인하지 않은 기존 잠금을 정산으로 소급 생성하지 않는다.

-- 예치 잔액 항목: 예치 총액 = 가용 + 잠금 + 정산 예약 + 지급 완료 (#107이 환불 예약·환불 완료 항목을 추가한다).
ALTER TABLE payment_deposits
 ADD COLUMN settlement_reserved_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
 ADD COLUMN settlement_paid_amount DECIMAL(19,2) NOT NULL DEFAULT 0;
ALTER TABLE payment_deposits DROP CHECK ck_deposit_balance;
ALTER TABLE payment_deposits ADD CONSTRAINT ck_deposit_balance CHECK (
 deposited_amount >= 0 AND locked_amount >= 0
 AND settlement_reserved_amount >= 0 AND settlement_paid_amount >= 0
 AND locked_amount + settlement_reserved_amount + settlement_paid_amount <= deposited_amount);

-- 잠금 금액이 정산으로 넘어간 잠금은 TRANSFERRED다. 매칭의 활성 잠금으로 남아 같은 매칭의 새 잠금을 막는다.
ALTER TABLE payment_locks DROP CHECK ck_payment_status;
ALTER TABLE payment_locks ADD CONSTRAINT ck_payment_status CHECK (status IN ('LOCKED', 'RELEASED', 'TRANSFERRED'));
ALTER TABLE payment_locks ADD COLUMN transferred_at DATETIME(6);

-- 업무 식별자는 근무(work_id)와 잠금(payment_lock_id)이다. 둘 다 정산 하나만 가질 수 있다.
CREATE TABLE payment_settlements (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 work_id BIGINT NOT NULL,
 payment_lock_id BIGINT NOT NULL,
 deposit_id BIGINT NOT NULL,
 matching_id BIGINT NOT NULL,
 job_post_id BIGINT NOT NULL,
 owner_member_id BIGINT NOT NULL,
 worker_member_id BIGINT NOT NULL,
 source_amount DECIMAL(19,2) NOT NULL,
 amount DECIMAL(19,2) NOT NULL,
 currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(20) NOT NULL,
 hold_reason VARCHAR(30),
 source_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 work_completed_at DATETIME(6) NOT NULL,
 payable_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 paid_at DATETIME(6),
 version BIGINT NOT NULL,
 UNIQUE KEY uk_settlement_work (work_id),
 UNIQUE KEY uk_settlement_payment_lock (payment_lock_id),
 KEY idx_settlement_payable (status, payable_at),
 KEY idx_settlement_owner (owner_member_id, status),
 KEY idx_settlement_worker (worker_member_id, status),
 CONSTRAINT fk_settlement_payment_lock FOREIGN KEY (payment_lock_id) REFERENCES payment_locks(id),
 CONSTRAINT fk_settlement_deposit FOREIGN KEY (deposit_id) REFERENCES payment_deposits(id),
 CONSTRAINT ck_settlement_amount CHECK (amount > 0 AND amount = FLOOR(amount) AND amount <= source_amount),
 CONSTRAINT ck_settlement_currency CHECK (currency = 'KRW'),
 CONSTRAINT ck_settlement_status CHECK (status IN ('RESERVED', 'ON_HOLD', 'PAYOUT_REQUESTED', 'PAYOUT_FAILED', 'PAID')),
 CONSTRAINT ck_settlement_hold CHECK ((status = 'ON_HOLD') = (hold_reason IS NOT NULL)),
 CONSTRAINT ck_settlement_paid CHECK ((status = 'PAID') = (paid_at IS NOT NULL))
);

CREATE TABLE payment_settlement_histories (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 settlement_id BIGINT NOT NULL,
 previous_status VARCHAR(20),
 next_status VARCHAR(20) NOT NULL,
 reason VARCHAR(30),
 occurred_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_settlement_history_settlement FOREIGN KEY (settlement_id) REFERENCES payment_settlements(id)
);

-- 잠금의 정산 이관은 Saga 명령이 아니므로 명령 키 대신 정산 ID로 원인을 남긴다. 기존 이력은 명령 키를 유지한다.
ALTER TABLE payment_status_histories
 MODIFY command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN settlement_id BIGINT,
 ADD CONSTRAINT fk_history_settlement FOREIGN KEY (settlement_id) REFERENCES payment_settlements(id),
 ADD CONSTRAINT ck_history_cause CHECK (command_key IS NOT NULL OR settlement_id IS NOT NULL);
