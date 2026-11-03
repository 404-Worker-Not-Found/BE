-- member-service TrustScoreUpdated로 받은 회원 신뢰 점수 사본(#126). 원본은 member-service가 소유한다.
-- 회원별 최신 revision만 유지하며 같은 revision의 재전달과 더 작은 revision은 반영하지 않는다.
CREATE TABLE member_trust_scores (
    member_id      BIGINT         NOT NULL,
    trust_score    DECIMAL(5, 2)  NOT NULL,
    review_count   INT            NOT NULL,
    policy_version VARCHAR(50)    NOT NULL,
    revision       BIGINT         NOT NULL,
    event_id       VARCHAR(128)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    received_at    DATETIME(6)    NOT NULL,
    PRIMARY KEY (member_id),
    CONSTRAINT ck_member_trust_scores_range CHECK (trust_score BETWEEN 0 AND 100),
    CONSTRAINT ck_member_trust_scores_reviews CHECK (review_count >= 1),
    CONSTRAINT ck_member_trust_scores_revision CHECK (revision >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
