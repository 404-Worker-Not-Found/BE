-- 점주 수동 마감 요청. 같은 Idempotency-Key의 재요청은 이 행에 저장한 처음 결과를 돌려준다.
CREATE TABLE job_close_requests (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    job_post_id     BIGINT       NOT NULL,
    owner_member_id BIGINT       NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    -- CLOSED: 이 요청이 마감함, ALREADY_CLOSED: 이미 마감된 공고라 바꾸지 않음
    result          VARCHAR(20)  NOT NULL,
    previous_status VARCHAR(20)  NOT NULL,
    processed_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_job_close_requests_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_job_close_requests_job_post
        FOREIGN KEY (job_post_id) REFERENCES job_posts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
