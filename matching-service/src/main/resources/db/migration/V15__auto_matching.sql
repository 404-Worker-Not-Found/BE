-- 점수 기반 자동 매칭과 폴백 매칭(#154).
-- 지원 승인 스냅샷의 지원 마감 시각과 모집 인원. V15 이전 지원은 값이 없어 자동 매칭 대상이 아니다.
ALTER TABLE applications
    ADD COLUMN application_deadline DATETIME(6) NULL AFTER work_starts_at,
    ADD COLUMN recruit_count        INT         NULL AFTER application_deadline,
    ADD CONSTRAINT ck_applications_recruit_count CHECK (recruit_count IS NULL OR recruit_count >= 1),
    ADD INDEX idx_applications_status_deadline (status, application_deadline);

-- 응답 제한시간이 지난 자동·폴백 제안을 찾는다.
ALTER TABLE matchings
    ADD INDEX idx_matchings_status_expires (status, expires_at);

-- 남은 자리가 있는데 제안할 후보가 없는 공고. 공고마다 한 번만 기록한다(재오픈 기능 없음).
CREATE TABLE matching_candidate_exhaustions (
    job_post_id     BIGINT      NOT NULL,
    owner_member_id BIGINT      NOT NULL,
    remaining_seats INT         NOT NULL,
    exhausted_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (job_post_id),
    CONSTRAINT ck_matching_candidate_exhaustions_seats CHECK (remaining_seats >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
