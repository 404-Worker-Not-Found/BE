ALTER TABLE job_posts
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE job_application_admissions (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    job_post_id      BIGINT       NOT NULL,
    worker_member_id BIGINT       NOT NULL,
    idempotency_key  VARCHAR(100) NOT NULL,
    job_version      BIGINT       NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    admitted_at      DATETIME(6)  NOT NULL,
    expires_at       DATETIME(6)  NOT NULL,
    consumed_at      DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_job_application_admissions_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_job_application_admissions_job_post
        FOREIGN KEY (job_post_id) REFERENCES job_posts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
