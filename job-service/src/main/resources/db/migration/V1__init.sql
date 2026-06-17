CREATE TABLE industry_categories (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    name       VARCHAR(255) NOT NULL,
    parent_id  BIGINT       NULL,
    is_active  BIT          NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE job_posts (
    id                   BIGINT         NOT NULL AUTO_INCREMENT,
    created_at           DATETIME(6)    NOT NULL,
    updated_at           DATETIME(6)    NOT NULL,
    business_id          BIGINT         NOT NULL,
    owner_id             BIGINT         NOT NULL,
    category_id          BIGINT         NOT NULL,
    store_name           VARCHAR(100)   NOT NULL,
    title                VARCHAR(120)   NOT NULL,
    description          VARCHAR(200)   NOT NULL,
    work_date            DATE           NOT NULL,
    start_time           TIME           NOT NULL,
    end_time             TIME           NOT NULL,
    base_hourly_wage     INT            NOT NULL,
    extra_wage           INT            NULL,
    recruit_count        INT            NOT NULL,
    latitude             DECIMAL(10, 7) NOT NULL,
    longitude            DECIMAL(10, 7) NOT NULL,
    urgency_level        VARCHAR(20)    NOT NULL,
    application_deadline DATETIME(6)    NOT NULL,
    status               VARCHAR(20)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_job_posts_category
        FOREIGN KEY (category_id) REFERENCES industry_categories (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE job_status_histories (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    job_post_id BIGINT       NOT NULL,
    from_status VARCHAR(20)  NOT NULL,
    to_status   VARCHAR(20)  NOT NULL,
    reason      VARCHAR(255) NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_job_status_histories_job_post
        FOREIGN KEY (job_post_id) REFERENCES job_posts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
