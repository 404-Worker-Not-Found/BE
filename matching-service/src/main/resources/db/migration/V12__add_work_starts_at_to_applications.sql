-- 지원 취소 기한(#103)을 판단하려고 지원할 때 받은 근무 시작 일시를 남긴다.
-- 공고의 근무 일시는 지원 승인 이력이 생긴 뒤에는 바뀌지 않으므로 지원 시점 값이 유지된다.
-- V12 이전 지원은 근무 일시를 알 수 없어 NULL로 두고 취소 기한을 검사하지 않는다.
-- 그런 지원도 근무 시작 뒤에는 job-service의 공고 마감 통지로 REJECTED가 된다.
ALTER TABLE applications ADD COLUMN work_starts_at DATETIME(6) NULL;
