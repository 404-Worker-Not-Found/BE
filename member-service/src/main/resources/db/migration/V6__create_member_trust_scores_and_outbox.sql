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

-- V6 이전에 받은 리뷰도 매칭 점수에 반영되도록 기존 평점 집계로 신뢰 점수(revision 1)와 TrustScoreUpdated를 한 번 채운다.
-- 계산식은 TrustScorePolicy(review-rating-v1)의 기본 설정(기본 평점 3.0, 보정 리뷰 수 3)과 같다. 설정을 바꿔 배포해도
-- 이 값은 마이그레이션 당시 기본값으로 계산되며, 해당 회원이 다음 리뷰를 받을 때 현재 설정으로 다시 계산된다.
-- (보정 평점 - 1) / 4 × 100 = (보정 평점 - 1) × 25. 반올림 경계에서 Java 계산과 같도록 충분한 소수 자릿수로 나눈다.
INSERT INTO member_trust_scores (member_id, trust_score, review_count, policy_version, revision, updated_at)
SELECT member_id,
       ROUND((CAST(3.0 * 3 + rating_sum AS DECIMAL(30, 10)) / (3 + review_count) - 1) * 25, 2),
       review_count,
       'review-rating-v1',
       1,
       NOW(6)
FROM member_review_stats
WHERE review_count >= 1;

-- 이벤트 ID는 회원별로 고정해 마이그레이션 재시도에서도 같은 이벤트를 가리킨다. 발행은 기존 relay가 맡는다.
INSERT INTO member_outbox (event_id, aggregate_type, aggregate_id, event_type, revision, schema_version, occurred_at,
                           payload, next_attempt_at)
SELECT CONCAT('trust-score-backfill-', member_id),
       'MEMBER',
       member_id,
       'TrustScoreUpdated',
       1,
       1,
       updated_at,
       JSON_OBJECT('eventId', CONCAT('trust-score-backfill-', member_id), 'eventType', 'TrustScoreUpdated',
                   'memberId', member_id, 'revision', 1, 'version', 1, 'trustScore', trust_score,
                   'reviewCount', review_count, 'policyVersion', policy_version),
       updated_at
FROM member_trust_scores;
