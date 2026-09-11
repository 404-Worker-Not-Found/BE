CREATE TABLE applications (
    id                           BIGINT      NOT NULL AUTO_INCREMENT,
    job_post_id                  BIGINT      NOT NULL,
    worker_member_id             BIGINT      NOT NULL,
    job_application_admission_id BIGINT      NOT NULL,
    status                       VARCHAR(20) NOT NULL,
    applied_at                   DATETIME(6) NOT NULL,
    version                      BIGINT      NOT NULL DEFAULT 0,
    revision                     BIGINT      NOT NULL DEFAULT 1,
    created_at                   DATETIME(6) NOT NULL,
    updated_at                   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_applications_job_post_worker
        UNIQUE (job_post_id, worker_member_id),
    CONSTRAINT uk_applications_admission
        UNIQUE (job_application_admission_id),
    CONSTRAINT ck_applications_status
        CHECK (status IN ('APPLIED', 'CANCELED', 'SELECTED', 'REJECTED')),
    CONSTRAINT ck_applications_version
        CHECK (version >= 0),
    CONSTRAINT ck_applications_revision
        CHECK (revision >= 1),
    INDEX idx_applications_worker_applied (worker_member_id, applied_at, id),
    INDEX idx_applications_job_status_applied (job_post_id, status, applied_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE application_status_histories (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    application_id  BIGINT       NOT NULL,
    from_status     VARCHAR(20)  NULL,
    to_status       VARCHAR(20)  NOT NULL,
    actor_type      VARCHAR(20)  NOT NULL,
    actor_member_id BIGINT       NULL,
    reason_code     VARCHAR(50)  NULL,
    reason_detail   VARCHAR(500) NULL,
    revision        BIGINT       NOT NULL,
    changed_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_application_status_histories_revision
        UNIQUE (application_id, revision),
    CONSTRAINT fk_application_status_histories_application
        FOREIGN KEY (application_id) REFERENCES applications (id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,
    CONSTRAINT ck_application_status_histories_from_status
        CHECK (from_status IS NULL OR from_status IN ('APPLIED', 'CANCELED', 'SELECTED', 'REJECTED')),
    CONSTRAINT ck_application_status_histories_to_status
        CHECK (to_status IN ('APPLIED', 'CANCELED', 'SELECTED', 'REJECTED')),
    CONSTRAINT ck_application_status_histories_status_change
        CHECK (from_status IS NULL OR from_status <> to_status),
    CONSTRAINT ck_application_status_histories_actor_type
        CHECK (actor_type IN ('WORKER', 'OWNER', 'SYSTEM')),
    CONSTRAINT ck_application_status_histories_actor_member
        CHECK (
            (actor_type = 'SYSTEM' AND actor_member_id IS NULL)
            OR (actor_type IN ('WORKER', 'OWNER') AND actor_member_id IS NOT NULL)
        ),
    CONSTRAINT ck_application_status_histories_revision
        CHECK (revision >= 1),
    INDEX idx_application_status_histories_changed (application_id, changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
