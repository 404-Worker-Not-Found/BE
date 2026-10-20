-- 만료 시각이 지난 RESERVED 지원 접수 승인을 상태 조건과 시각 범위로 찾는다.
-- 사용 완료(CONSUMED)·만료(EXPIRED) 승인이 쌓여도 만료 대상만 인덱스 범위로 읽는다. 보조 인덱스는 기본 키(id)를 포함한다.
CREATE INDEX idx_job_application_admissions_status_expires ON job_application_admissions (status, expires_at);
