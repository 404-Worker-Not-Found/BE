-- 공고에 연결한 결제 주문과 그 주문을 만든 원래 결제 스냅샷. 주문 생성 결과가 검증되기 전에는 모두 NULL이다.
-- 이 마이그레이션 이전에 생성된 공고는 주문과 명령 없이 기존 상태를 유지한다.
ALTER TABLE job_posts
    ADD COLUMN payment_order_id    VARCHAR(64) NULL,
    ADD COLUMN payment_job_version BIGINT      NULL,
    ADD COLUMN payment_amount      BIGINT      NULL,
    ADD COLUMN payment_currency    VARCHAR(3)  NULL,
    ADD CONSTRAINT ck_job_posts_payment_order_snapshot CHECK (
        (payment_order_id IS NULL AND payment_job_version IS NULL
            AND payment_amount IS NULL AND payment_currency IS NULL)
        OR (payment_order_id IS NOT NULL AND payment_job_version IS NOT NULL
            AND payment_amount IS NOT NULL AND payment_currency IS NOT NULL)
    );

CREATE TABLE job_payment_order_commands (
    id                       BIGINT      NOT NULL AUTO_INCREMENT,
    created_at               DATETIME(6) NOT NULL,
    updated_at               DATETIME(6) NOT NULL,
    job_post_id              BIGINT      NOT NULL,
    issue_sequence           INT         NOT NULL,
    idempotency_key          VARCHAR(36) NOT NULL,
    job_version              BIGINT      NOT NULL,
    owner_member_id          BIGINT      NOT NULL,
    amount                   BIGINT      NOT NULL,
    currency                 VARCHAR(3)  NOT NULL,
    status                   VARCHAR(20) NOT NULL,
    order_id                 VARCHAR(64) NULL,
    attempt_count            INT         NOT NULL,
    next_attempt_at          DATETIME(6) NOT NULL,
    lease_token              VARCHAR(36) NULL,
    lease_expires_at         DATETIME(6) NULL,
    last_attempted_at        DATETIME(6) NULL,
    last_failure_type        VARCHAR(30) NULL,
    last_failure_http_status INT         NULL,
    last_failure_code        VARCHAR(50) NULL,
    completed_at             DATETIME(6) NULL,
    PRIMARY KEY (id),
    -- payment-service로 보내는 Idempotency-Key. 한 키는 한 번의 주문 생성 명령에만 쓴다.
    CONSTRAINT uk_job_payment_order_commands_idempotency_key UNIQUE (idempotency_key),
    -- 공고별 명령 발급 순번. 재시도는 같은 행을 쓰고, 재결제는 다음 순번의 새 명령을 발급한다.
    CONSTRAINT uk_job_payment_order_commands_job_sequence UNIQUE (job_post_id, issue_sequence),
    -- 한 주문은 한 명령에만 연결된다.
    CONSTRAINT uk_job_payment_order_commands_order_id UNIQUE (order_id),
    CONSTRAINT ck_job_payment_order_commands_amount CHECK (amount >= 100),
    CONSTRAINT ck_job_payment_order_commands_currency CHECK (currency = 'KRW'),
    CONSTRAINT fk_job_payment_order_commands_job_post
        FOREIGN KEY (job_post_id) REFERENCES job_posts (id),
    -- 전송 대상(미완료이고 다음 시도 시각이 지난 명령)을 찾는 데 사용한다.
    INDEX idx_job_payment_order_commands_due (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
