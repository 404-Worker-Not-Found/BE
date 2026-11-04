-- 공고 지원자 수(applicantCount) projection. 원본은 matching-service applications다.
--
-- job_application_projections: 지원(applicationId)별 마지막으로 반영한 상태와 revision.
-- matching 지원 이벤트와 공고별 지원 상태 스냅샷을 같은 revision 비교로 반영한다. 더 큰 revision만 상태를 바꾸고, 같은 revision은
-- 바꾸지 않으며, 작은 revision은 무시한다. event_id는 마지막으로 상태를 바꾼 이벤트 ID이며 스냅샷으로 바뀌면 NULL이다.
-- 지원자 수는 (job_post_id, status) 인덱스로 APPLIED 행을 센다.
CREATE TABLE job_application_projections (
    application_id BIGINT       NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    job_post_id    BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    revision       BIGINT       NOT NULL,
    event_id       VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (application_id),
    INDEX idx_job_application_projections_job_status (job_post_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 공고별 projection 적재 상태. 같은 공고의 이벤트 반영과 스냅샷 반영은 이 행을 잠가 직렬화한다.
-- synced_at: 마지막으로 반영한 스냅샷의 조회 시작 시각. NULL(또는 행 없음)이면 초기 적재 전이라 applicantCount를 null로 둔다.
-- changed_at: 이벤트로 projection이 마지막으로 바뀐 시각. synced_at보다 늦으면 다음 정합성 비교 대상이다.
CREATE TABLE job_applicant_count_states (
    job_post_id BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    synced_at   DATETIME(6) NULL,
    changed_at  DATETIME(6) NULL,
    PRIMARY KEY (job_post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
