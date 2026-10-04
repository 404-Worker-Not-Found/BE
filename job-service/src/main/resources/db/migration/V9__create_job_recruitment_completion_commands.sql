CREATE TABLE job_recruitment_completion_commands (
    id                       BIGINT      NOT NULL AUTO_INCREMENT,
    created_at               DATETIME(6) NOT NULL,
    updated_at               DATETIME(6) NOT NULL,
    command_id               VARCHAR(36) NOT NULL,
    job_post_id              BIGINT      NOT NULL,
    job_version              BIGINT      NOT NULL,
    status                   VARCHAR(20) NOT NULL,
    attempt_count            INT         NOT NULL,
    next_attempt_at          DATETIME(6) NOT NULL,
    lease_token              VARCHAR(36) NULL,
    lease_expires_at         DATETIME(6) NULL,
    last_attempted_at        DATETIME(6) NULL,
    last_failure_type        VARCHAR(30) NULL,
    last_failure_http_status INT         NULL,
    last_failure_code        VARCHAR(50) NULL,
    succeeded_at             DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_job_recruitment_completion_commands_command_id UNIQUE (command_id),
    -- 한 공고의 같은 모집 완료 전이(완료 버전)에는 명령을 하나만 만든다.
    CONSTRAINT uk_job_recruitment_completion_commands_job_version UNIQUE (job_post_id, job_version),
    CONSTRAINT fk_job_recruitment_completion_commands_job_post
        FOREIGN KEY (job_post_id) REFERENCES job_posts (id),
    -- 전송 대상(미완료이고 다음 시도 시각이 지난 명령)을 찾는 데 사용한다.
    INDEX idx_job_recruitment_completion_commands_due (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
