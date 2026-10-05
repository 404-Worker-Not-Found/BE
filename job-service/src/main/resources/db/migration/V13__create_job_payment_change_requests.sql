-- 점주의 결제 조건 변경·재결제 요청. 공고의 현재 조건과 주문 연결은 새 주문이 검증·연결될 때까지 바꾸지 않고,
-- 적용할 조건 스냅샷을 이 행에 따로 보관한다. 요청마다 다음 순번의 주문 생성 명령(command_id) 하나를 발급한다.
CREATE TABLE job_payment_change_requests (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    job_post_id          BIGINT       NOT NULL,
    owner_member_id      BIGINT       NOT NULL,
    -- 점주 요청의 Idempotency-Key. 같은 키의 재요청은 이 행의 현재 처리 상태를 돌려준다.
    idempotency_key      VARCHAR(100) NOT NULL,
    change_type          VARCHAR(20)  NOT NULL,
    command_id           BIGINT       NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    -- 새 주문이 연결되면 공고에 적용할 결제 조건. 재결제는 요청 당시 조건과 같다.
    work_date            DATE         NOT NULL,
    start_time           TIME         NOT NULL,
    end_time             TIME         NOT NULL,
    end_time_next_day    BOOLEAN      NOT NULL,
    base_hourly_wage     INT          NOT NULL,
    extra_wage           INT          NULL,
    recruit_count        INT          NOT NULL,
    application_deadline DATETIME(6)  NOT NULL,
    resolution_code      VARCHAR(50)  NULL,
    resolved_at          DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_job_payment_change_requests_idempotency_key UNIQUE (idempotency_key),
    -- 한 명령은 한 요청에만 속한다.
    CONSTRAINT uk_job_payment_change_requests_command UNIQUE (command_id),
    CONSTRAINT fk_job_payment_change_requests_job_post
        FOREIGN KEY (job_post_id) REFERENCES job_posts (id),
    CONSTRAINT fk_job_payment_change_requests_command
        FOREIGN KEY (command_id) REFERENCES job_payment_order_commands (id),
    -- 점주 결제 주문 조회의 최근 요청 확인에 사용한다.
    INDEX idx_job_payment_change_requests_job (job_post_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
