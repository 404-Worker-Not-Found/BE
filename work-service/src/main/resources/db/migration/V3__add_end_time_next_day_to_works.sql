ALTER TABLE works
    ADD COLUMN end_time_next_day BIT NOT NULL DEFAULT 0 AFTER end_time;

-- 기존 예정 근무는 익일 여부를 받지 않았다. job-service는 익일 근무를 종료 시각이 시작 시각 이하인 구간으로만 저장한다.
UPDATE works
SET end_time_next_day = (end_time <= start_time);
