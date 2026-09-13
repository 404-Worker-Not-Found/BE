CREATE TABLE matchings (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    application_id    BIGINT       NOT NULL,
    job_post_id       BIGINT       NOT NULL,
    owner_member_id   BIGINT       NOT NULL,
    worker_member_id  BIGINT       NOT NULL,
    score_batch_id    BIGINT       NULL,
    score_snapshot_id BIGINT       NULL,
    selection_type    VARCHAR(20)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    selected_at       DATETIME(6)  NOT NULL,
    expires_at        DATETIME(6)  NULL,
    confirmed_at      DATETIME(6)  NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    revision          BIGINT       NOT NULL DEFAULT 1,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_matchings_application UNIQUE (application_id),
    CONSTRAINT fk_matchings_application
        FOREIGN KEY (application_id) REFERENCES applications (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_matchings_score_batch
        FOREIGN KEY (score_batch_id) REFERENCES matching_score_batches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_matchings_score_snapshot
        FOREIGN KEY (score_snapshot_id) REFERENCES matching_score_snapshots (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_matchings_selection_type
        CHECK (selection_type IN ('MANUAL', 'AUTOMATIC', 'FALLBACK')),
    CONSTRAINT ck_matchings_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'DECLINED', 'EXPIRED', 'CANCELED')),
    CONSTRAINT ck_matchings_revision CHECK (revision >= 1),
    CONSTRAINT ck_matchings_version CHECK (version >= 0),
    CONSTRAINT ck_matchings_confirmed_at
        CHECK ((status = 'CONFIRMED' AND confirmed_at IS NOT NULL) OR status <> 'CONFIRMED'),
    INDEX idx_matchings_job_status_selected (job_post_id, status, selected_at, id),
    INDEX idx_matchings_worker_status_selected (worker_member_id, status, selected_at, id),
    INDEX idx_matchings_owner_job_status (owner_member_id, job_post_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE matching_status_histories (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    matching_id     BIGINT       NOT NULL,
    from_status     VARCHAR(20)  NULL,
    to_status       VARCHAR(20)  NOT NULL,
    actor_type      VARCHAR(20)  NOT NULL,
    actor_member_id BIGINT       NULL,
    reason_code     VARCHAR(50)  NULL,
    reason_detail   VARCHAR(500) NULL,
    revision        BIGINT       NOT NULL,
    changed_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_matching_status_histories_revision UNIQUE (matching_id, revision),
    CONSTRAINT fk_matching_status_histories_matching
        FOREIGN KEY (matching_id) REFERENCES matchings (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_matching_status_histories_from_status
        CHECK (from_status IS NULL OR from_status IN ('PENDING', 'CONFIRMED', 'DECLINED', 'EXPIRED', 'CANCELED')),
    CONSTRAINT ck_matching_status_histories_to_status
        CHECK (to_status IN ('PENDING', 'CONFIRMED', 'DECLINED', 'EXPIRED', 'CANCELED')),
    CONSTRAINT ck_matching_status_histories_status_change
        CHECK (from_status IS NULL OR from_status <> to_status),
    CONSTRAINT ck_matching_status_histories_actor_type
        CHECK (actor_type IN ('WORKER', 'OWNER', 'SYSTEM')),
    CONSTRAINT ck_matching_status_histories_actor_member
        CHECK (
            (actor_type = 'SYSTEM' AND actor_member_id IS NULL)
            OR (actor_type IN ('WORKER', 'OWNER') AND actor_member_id IS NOT NULL)
        ),
    CONSTRAINT ck_matching_status_histories_revision CHECK (revision >= 1),
    INDEX idx_matching_status_histories_changed (matching_id, changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
