-- 노쇼·확정 근무 취소로 해제한 잠금 금액의 자동 환불(#167). 종료 처리와 같은 트랜잭션에서 만든 환불과 그 결과를 남긴다.
-- 기능이 꺼져 있거나 해제하지 않은 종료(검토 대상·이미 해제)는 두 컬럼이 NULL이다.
ALTER TABLE payment_work_terminations
 ADD COLUMN refund_id BIGINT NULL,
 ADD COLUMN refund_outcome VARCHAR(64) NULL,
 ADD CONSTRAINT fk_termination_refund FOREIGN KEY (refund_id) REFERENCES payment_refunds(id),
 ADD CONSTRAINT uk_termination_refund UNIQUE (refund_id),
 ADD CONSTRAINT ck_termination_refund CHECK (refund_id IS NULL OR refund_outcome = 'REQUESTED');
