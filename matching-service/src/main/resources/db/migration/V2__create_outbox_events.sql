CREATE TABLE outbox_events (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    event_id       VARCHAR(36)   NOT NULL,
    aggregate_type VARCHAR(50)   NOT NULL,
    aggregate_id   BIGINT        NOT NULL,
    event_type     VARCHAR(100)  NOT NULL,
    correlation_id VARCHAR(36)   NOT NULL,
    revision       BIGINT        NOT NULL,
    schema_version INT           NOT NULL,
    payload        JSON          NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    occurred_at    DATETIME(6)   NOT NULL,
    published_at   DATETIME(6)   NULL,
    retry_count    INT           NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outbox_events_event_id
        UNIQUE (event_id),
    CONSTRAINT uk_outbox_events_aggregate_revision
        UNIQUE (aggregate_type, aggregate_id, revision),
    CONSTRAINT ck_outbox_events_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_events_revision
        CHECK (revision >= 1),
    CONSTRAINT ck_outbox_events_schema_version
        CHECK (schema_version >= 1),
    CONSTRAINT ck_outbox_events_retry_count
        CHECK (retry_count >= 0),
    INDEX idx_outbox_events_status_occurred (status, occurred_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
