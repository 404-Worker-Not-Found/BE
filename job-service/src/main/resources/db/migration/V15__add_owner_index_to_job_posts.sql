-- 점주 본인 공고 목록(GET /api/jobs/me)을 점주 조건과 등록 최신순(created_at DESC, id DESC) 정렬로 페이지 조회한다.
-- 정렬 열까지 인덱스에 두어 점주의 공고 전체를 읽고 정렬하지 않고 인덱스 순서로 필요한 페이지만 읽는다.
CREATE INDEX idx_job_posts_owner_created ON job_posts (owner_id, created_at, id);
