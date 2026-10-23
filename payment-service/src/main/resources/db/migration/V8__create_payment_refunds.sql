-- 예치금 환불 기반 모델(#107 첫 단위). 환불 가능액을 예약만 하고 PG 환불 호출과 결과 복구는 후속 단위가 연결한다.

-- 예치 잔액 항목: 예치 총액 = 가용 + 잠금 + 정산 예약 + 지급 완료 + 환불 예약 + 환불 완료.
-- 환불은 예치 총액을 줄이지 않고 환불 항목으로 옮긴다. 기존 예치의 두 항목은 0에서 시작한다.
ALTER TABLE payment_deposits
 ADD COLUMN refund_reserved_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
 ADD COLUMN refunded_amount DECIMAL(19,2) NOT NULL DEFAULT 0;
ALTER TABLE payment_deposits DROP CHECK ck_deposit_balance;
ALTER TABLE payment_deposits ADD CONSTRAINT ck_deposit_balance CHECK (
 deposited_amount >= 0 AND locked_amount >= 0
 AND settlement_reserved_amount >= 0 AND settlement_paid_amount >= 0
 AND refund_reserved_amount >= 0 AND refunded_amount >= 0
 AND locked_amount + settlement_reserved_amount + settlement_paid_amount
   + refund_reserved_amount + refunded_amount <= deposited_amount);

-- 환불 하나는 예치 하나와 그 예치를 만든 결제 주문 하나에 속한다. 금액은 정수 KRW만 허용한다.
CREATE TABLE payment_refunds (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 deposit_id BIGINT NOT NULL,
 order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 job_post_id BIGINT NOT NULL,
 owner_member_id BIGINT NOT NULL,
 amount DECIMAL(19,2) NOT NULL,
 currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reason VARCHAR(200) NOT NULL,
 status VARCHAR(20) NOT NULL,
 requested_at DATETIME(6) NOT NULL,
 completed_at DATETIME(6),
 version BIGINT NOT NULL,
 KEY idx_refund_job (job_post_id),
 KEY idx_refund_owner (owner_member_id, status),
 CONSTRAINT fk_refund_deposit FOREIGN KEY (deposit_id) REFERENCES payment_deposits(id),
 CONSTRAINT fk_refund_order FOREIGN KEY (order_id) REFERENCES payment_orders(id),
 CONSTRAINT ck_refund_amount CHECK (amount > 0 AND amount = FLOOR(amount)),
 CONSTRAINT ck_refund_currency CHECK (currency = 'KRW'),
 CONSTRAINT ck_refund_status CHECK (status IN ('REQUESTED', 'SUCCEEDED', 'FAILED')),
 CONSTRAINT ck_refund_completed CHECK ((status = 'REQUESTED') = (completed_at IS NULL))
);

-- 환불 요청 명령. 잠금 명령(payment_commands)과 같이 성공 결과와 업무 거절을 함께 보관한다.
CREATE TABLE payment_refund_commands (
 command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 refund_id BIGINT,
 rejection VARCHAR(50),
 CONSTRAINT fk_refund_command_refund FOREIGN KEY (refund_id) REFERENCES payment_refunds(id),
 CONSTRAINT ck_refund_command_result CHECK (refund_id IS NULL OR rejection IS NULL)
);

CREATE TABLE payment_refund_histories (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 refund_id BIGINT NOT NULL,
 previous_status VARCHAR(20),
 next_status VARCHAR(20) NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_refund_history_refund FOREIGN KEY (refund_id) REFERENCES payment_refunds(id)
);
