-- MatchConfirmed 수신 기록과 정산 대상 기록 결과. 정산 대상과 같은 트랜잭션에서 저장하고 커밋된 뒤에만 Redis Stream에 ACK한다.
-- 같은 eventId가 다시 오면 fingerprint로 같은 내용인지 확인하고 저장된 결과를 돌려준다. 거절 결과도 기록해 같은 매칭의 근무 완료를
-- 영구 거절로 판단하는 근거로 쓴다. 해석할 수 없는 이벤트와 일시 오류로 롤백된 처리는 기록하지 않는다.
CREATE TABLE payment_matching_event_receipts (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 matching_id BIGINT NOT NULL,
 revision BIGINT NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result VARCHAR(40) NOT NULL,
 received_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_matching_event_receipt_event (event_id),
 KEY idx_matching_event_receipt_matching (matching_id)
);
