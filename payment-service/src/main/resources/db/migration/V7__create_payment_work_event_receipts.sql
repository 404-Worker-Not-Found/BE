-- WorkCompleted 수신 기록과 정산 예약 결과. 정산 생성·금액 이관과 같은 트랜잭션에서 저장하고 커밋된 뒤에만 Redis Stream에 ACK한다.
-- 같은 eventId가 다시 오면 fingerprint로 같은 내용인지 확인하고 저장된 결과를 돌려준다. eventId가 달라도 같은 근무의 정산은
-- payment_settlements의 work_id 유일 제약으로 하나만 생긴다. 영구 거절도 결과로 기록한다.
-- 해석할 수 없는 이벤트, 선행 MatchConfirmed 미도착, 일시 오류로 롤백된 처리는 기록하지 않고 pending으로 남는다.
-- matching_id는 이벤트에 비어 있으면 거절 결과와 함께 NULL로 남는다.
CREATE TABLE payment_work_event_receipts (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 work_id BIGINT NOT NULL,
 matching_id BIGINT,
 revision BIGINT NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result VARCHAR(40) NOT NULL,
 settlement_id BIGINT,
 received_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_work_event_receipt_event (event_id),
 KEY idx_work_event_receipt_work (work_id),
 CONSTRAINT fk_work_event_receipt_settlement FOREIGN KEY (settlement_id) REFERENCES payment_settlements(id)
);
