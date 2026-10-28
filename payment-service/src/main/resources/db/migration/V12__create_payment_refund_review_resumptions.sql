-- 검토 대상 환불의 운영자 수동 재개(#108 첫 단위). 재개 명령이 운영자·사유 감사 기록을 겸한다.

-- 성공한 명령은 재개한 검토 사유(resumed_reason)를, 업무 거절은 거절 코드(rejection)를 남긴다.
-- 같은 키는 처음 판단을 돌려주고, 거절된 키는 나중에 조건이 바뀌어도 성공하지 않는다.
CREATE TABLE payment_refund_review_resumptions (
 command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 refund_id BIGINT NOT NULL,
 operator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 note VARCHAR(200) NOT NULL,
 resumed_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 rejection VARCHAR(50) NULL,
 requested_at DATETIME(6) NOT NULL,
 KEY idx_refund_resumption_refund (refund_id),
 CONSTRAINT fk_refund_resumption_refund FOREIGN KEY (refund_id) REFERENCES payment_refunds(id),
 CONSTRAINT ck_refund_resumption_result CHECK (resumed_reason IS NULL OR rejection IS NULL)
);
