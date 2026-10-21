-- 예치금 환불 PG 실행(#107 두 번째 단위). 환불별 실행 임대·재시도 시각과 PG 결과 기록을 추가한다.

-- attempts: PG 취소를 보내려고 점유한 횟수. 1 이상이면 PG에 요청이 갔을 수 있으므로 결과 확인 전까지 진행 중 환불이다.
-- first_attempted_at: 토스 멱등 키 유효 기간(15일) 안에서만 같은 키로 재시도하기 위한 기준 시각.
-- next_attempt_at: 비어 있으면 아직 보낸 적 없는 환불로 바로 실행 대상이다.
-- review_required: 금액·식별 정보가 장부와 맞지 않아 자동 실행을 멈춘 환불. 금액은 환불 예약에 그대로 남는다.
ALTER TABLE payment_refunds
 ADD COLUMN attempts INT NOT NULL DEFAULT 0,
 ADD COLUMN next_attempt_at DATETIME(6) NULL,
 ADD COLUMN lease_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN lease_until DATETIME(6) NULL,
 ADD COLUMN first_attempted_at DATETIME(6) NULL,
 ADD COLUMN review_required BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN provider_transaction_key VARCHAR(64) NULL;

ALTER TABLE payment_refunds
 ADD CONSTRAINT ck_refund_attempts CHECK (attempts >= 0),
 ADD CONSTRAINT ck_refund_failure CHECK (status <> 'FAILED' OR failure_code IS NOT NULL),
 ADD KEY idx_refund_due (status, review_required, next_attempt_at),
 ADD KEY idx_refund_order (order_id, status, id);

-- 검토 전환처럼 상태가 같은 기록의 원인을 남긴다.
ALTER TABLE payment_refund_histories ADD COLUMN reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
