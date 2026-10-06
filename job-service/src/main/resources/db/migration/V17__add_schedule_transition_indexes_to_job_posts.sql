-- 지원 마감·근무 시작이 지난 공고의 자동 상태 전이 대상을 상태 조건과 시각 범위로 찾는다.
-- 모집이 끝난 CLOSED 공고가 쌓여도 대상 상태의 지난 공고만 인덱스 범위로 읽는다. 보조 인덱스는 기본 키(id)를 포함한다.
CREATE INDEX idx_job_posts_status_deadline ON job_posts (status, application_deadline);
CREATE INDEX idx_job_posts_status_work_start ON job_posts (status, work_date, start_time);
