-- 받은 리뷰 평점 기반 신뢰 점수(#126 두 번째 단위, 임시 정책).
-- member_trust_scores: 회원별 현재 신뢰 점수. 리뷰 저장·평점 집계와 같은 트랜잭션에서 갱신하며 revision은 변경마다 1씩 증가한다.
CREATE TABLE member_trust_scores (
 member_id BIGINT NOT NULL PRIMARY KEY,
 trust_score DECIMAL(5, 2) NOT NULL,
 review_count INT NOT NULL,
 policy_version VARCHAR(50) NOT NULL,
 revision BIGINT NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT ck_member_trust_score_range CHECK (trust_score BETWEEN 0 AND 100),
 CONSTRAINT ck_member_trust_score_reviews CHECK (review_count >= 1),
 CONSTRAINT ck_member_trust_score_revision CHECK (revision >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- member_outbox: 회원 도메인 이벤트. 상태 변경과 같은 트랜잭션에 저장하고 relay가 Redis Stream으로 발행한다.
CREATE TABLE member_outbox (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 aggregate_type VARCHAR(30) NOT NULL,
 aggregate_id BIGINT NOT NULL,
 event_type VARCHAR(50) NOT NULL,
 revision BIGINT NOT NULL,
 schema_version INT NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 payload JSON NOT NULL,
 next_attempt_at DATETIME(6) NOT NULL,
 retry_count INT NOT NULL DEFAULT 0,
 lease_token VARCHAR(36) NULL,
 lease_until DATETIME(6) NULL,
 published_at DATETIME(6) NULL,
 CONSTRAINT uk_member_outbox_event UNIQUE (event_id),
 KEY idx_member_outbox_unpublished (published_at, next_attempt_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
