-- 근무 완료 기반 상호 리뷰(#126).
-- reviewable_works: work-service WorkCompleted로 받은 리뷰 가능한 근무. 근무당 한 행이며 같은 이벤트는 한 번만 기록한다.
CREATE TABLE reviewable_works (
 work_id BIGINT NOT NULL PRIMARY KEY,
 event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 job_post_id BIGINT NOT NULL,
 owner_member_id BIGINT NOT NULL,
 worker_member_id BIGINT NOT NULL,
 work_date DATE NULL,
 completed_at DATETIME(6) NOT NULL,
 received_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_reviewable_work_event UNIQUE (event_id),
 CONSTRAINT ck_reviewable_work_parties CHECK (owner_member_id <> worker_member_id),
 KEY idx_reviewable_work_owner (owner_member_id, completed_at),
 KEY idx_reviewable_work_worker (worker_member_id, completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- member_reviews: 근무 참여자가 상대방에게 남긴 리뷰. 근무·작성자마다 하나다. 평점은 1~5 정수(임시 정책).
CREATE TABLE member_reviews (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 work_id BIGINT NOT NULL,
 reviewer_member_id BIGINT NOT NULL,
 reviewee_member_id BIGINT NOT NULL,
 reviewer_role VARCHAR(20) NOT NULL,
 rating INT NOT NULL,
 comment VARCHAR(500) NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_member_review_work_reviewer UNIQUE (work_id, reviewer_member_id),
 CONSTRAINT fk_member_review_work FOREIGN KEY (work_id) REFERENCES reviewable_works(work_id),
 CONSTRAINT ck_member_review_rating CHECK (rating BETWEEN 1 AND 5),
 CONSTRAINT ck_member_review_role CHECK (reviewer_role IN ('OWNER','WORKER')),
 CONSTRAINT ck_member_review_parties CHECK (reviewer_member_id <> reviewee_member_id),
 KEY idx_member_review_reviewee (reviewee_member_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- member_review_stats: 회원이 받은 리뷰 수와 평점 합. 리뷰 저장과 같은 트랜잭션에서 갱신하며 신뢰 점수의 입력이 된다.
CREATE TABLE member_review_stats (
 member_id BIGINT NOT NULL PRIMARY KEY,
 review_count INT NOT NULL,
 rating_sum INT NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT ck_member_review_stats CHECK (review_count >= 0 AND rating_sum >= review_count AND rating_sum <= review_count * 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
