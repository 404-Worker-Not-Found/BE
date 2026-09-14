CREATE TABLE recruitment_states (
    job_post_id          BIGINT       NOT NULL,
    completed_job_version BIGINT      NULL,
    completion_command_id VARCHAR(36) NULL,
    completed_at         DATETIME(6)  NULL,
    PRIMARY KEY (job_post_id),
    CONSTRAINT ck_recruitment_states_completed_version
        CHECK (completed_job_version IS NULL OR completed_job_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
