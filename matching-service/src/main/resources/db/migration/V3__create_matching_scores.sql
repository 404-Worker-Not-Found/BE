CREATE TABLE matching_score_batches (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    job_post_id    BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    policy_version VARCHAR(50)  NOT NULL,
    model_version  VARCHAR(50)  NULL,
    started_at     DATETIME(6)  NOT NULL,
    completed_at   DATETIME(6)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_matching_score_batches_status
        CHECK (status IN ('CALCULATING', 'READY', 'FAILED')),
    CONSTRAINT ck_matching_score_batches_completed_at
        CHECK (
            (status = 'CALCULATING' AND completed_at IS NULL)
            OR (status IN ('READY', 'FAILED') AND completed_at IS NOT NULL)
        ),
    INDEX idx_matching_score_batches_job_status_completed
        (job_post_id, status, completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE matching_score_snapshots (
    id                       BIGINT        NOT NULL AUTO_INCREMENT,
    score_batch_id           BIGINT        NOT NULL,
    application_id           BIGINT        NOT NULL,
    calculation_status       VARCHAR(20)   NOT NULL,
    total_score              DECIMAL(9, 4) NULL,
    applied_time_score       DECIMAL(9, 4) NULL,
    rating_score             DECIMAL(9, 4) NULL,
    experience_score         DECIMAL(9, 4) NULL,
    activity_score           DECIMAL(9, 4) NULL,
    arrival_score            DECIMAL(9, 4) NULL,
    no_show_score            DECIMAL(9, 4) NULL,
    expected_arrival_minutes INT           NULL,
    no_show_probability      DECIMAL(6, 5) NULL,
    input_snapshot           JSON          NOT NULL,
    missing_inputs           JSON          NULL,
    calculated_at            DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_matching_score_snapshots_batch_application
        UNIQUE (score_batch_id, application_id),
    CONSTRAINT fk_matching_score_snapshots_batch
        FOREIGN KEY (score_batch_id) REFERENCES matching_score_batches (id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,
    CONSTRAINT fk_matching_score_snapshots_application
        FOREIGN KEY (application_id) REFERENCES applications (id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,
    CONSTRAINT ck_matching_score_snapshots_status
        CHECK (calculation_status IN ('PENDING', 'READY', 'FAILED')),
    CONSTRAINT ck_matching_score_snapshots_total_score
        CHECK (
            (calculation_status = 'READY' AND total_score IS NOT NULL)
            OR (calculation_status IN ('PENDING', 'FAILED') AND total_score IS NULL)
        ),
    INDEX idx_matching_score_snapshots_application_calculated
        (application_id, calculated_at),
    INDEX idx_matching_score_snapshots_batch_score
        (score_batch_id, total_score DESC, application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
