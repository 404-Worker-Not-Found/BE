-- matching-service ApplicationSubmitted 이벤트의 영구 수신 기록과 처리 결과.
-- 승인 사용 완료 처리와 같은 트랜잭션에서 저장하고, 커밋된 뒤에만 Redis Stream에 ACK한다. 같은 eventId가 다시 오면 이 행으로
-- 중복을 판단해 승인을 다시 바꾸지 않는다. 영구 거절과 만료 뒤 지원도 결과로 기록한다.
-- 해석할 수 없는 envelope, 일시 오류로 롤백된 처리는 기록하지 않는다. 기록되지 않은 이벤트는 ACK하지 않고 pending으로 남는다.
-- admission_id·job_post_id·worker_member_id·occurred_at은 이벤트에 비어 있으면 거절 결과와 함께 NULL로 남는다.
CREATE TABLE job_admission_event_receipts (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    event_id         VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    application_id   BIGINT       NOT NULL,
    revision         BIGINT       NOT NULL,
    admission_id     BIGINT       NULL,
    job_post_id      BIGINT       NULL,
    worker_member_id BIGINT       NULL,
    occurred_at      DATETIME(6)  NULL,
    result           VARCHAR(40)  NOT NULL,
    received_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_job_admission_event_receipts_event_id UNIQUE (event_id),
    INDEX idx_job_admission_event_receipts_admission_id (admission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
