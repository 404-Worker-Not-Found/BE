-- 노쇼 이력 기반 신뢰 점수 감점(#164, 임시 정책 review-rating-no-show-v1).
-- member_no_show_works: work-service WorkNoShow로 받은 노쇼 근무. 근무당 한 행이며 같은 이벤트는 한 번만 감점한다.
CREATE TABLE member_no_show_works (
 work_id BIGINT NOT NULL PRIMARY KEY,
 event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 job_post_id BIGINT NOT NULL,
 owner_member_id BIGINT NOT NULL,
 worker_member_id BIGINT NOT NULL,
 work_date DATE NULL,
 no_show_at DATETIME(6) NOT NULL,
 received_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_member_no_show_work_event UNIQUE (event_id),
 CONSTRAINT ck_member_no_show_work_parties CHECK (owner_member_id <> worker_member_id),
 KEY idx_member_no_show_work_worker (worker_member_id, no_show_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 회원별 신뢰 점수 입력(받은 리뷰 수·평점 합·노쇼 횟수)을 한 행에 둔다. 리뷰와 노쇼가 같은 행 잠금으로 직렬화되어
-- 신뢰 점수 revision과 이벤트 순서가 입력 갱신 순서와 같다. 노쇼만 있는 회원은 리뷰 수·평점 합이 0인 행을 가진다.
ALTER TABLE member_review_stats ADD COLUMN no_show_count INT NOT NULL DEFAULT 0 AFTER rating_sum;
ALTER TABLE member_review_stats ADD CONSTRAINT ck_member_review_stats_no_shows CHECK (no_show_count >= 0);

-- 리뷰 없이 노쇼만 있는 회원도 신뢰 점수를 가진다. 기존 행은 노쇼가 0회이므로 점수와 policy_version을 바꾸지 않으며,
-- 다음 리뷰·노쇼 때 새 정책으로 다시 계산된다. 배포 전 노쇼는 새 소비자 그룹이 Stream 처음부터 읽어 반영한다.
ALTER TABLE member_trust_scores ADD COLUMN no_show_count INT NOT NULL DEFAULT 0 AFTER review_count;
ALTER TABLE member_trust_scores DROP CHECK ck_member_trust_score_reviews;
ALTER TABLE member_trust_scores ADD CONSTRAINT ck_member_trust_score_inputs
 CHECK (review_count >= 0 AND no_show_count >= 0 AND (review_count >= 1 OR no_show_count >= 1));
