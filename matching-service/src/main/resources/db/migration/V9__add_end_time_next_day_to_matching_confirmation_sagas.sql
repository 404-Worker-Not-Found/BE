ALTER TABLE matching_confirmation_sagas
    ADD COLUMN end_time_next_day BIT NULL AFTER end_time;

-- job-service는 익일 근무를 종료 시각이 시작 시각 이하인 구간으로만 저장하므로, 이미 기록된 자리 스냅샷은 시각으로 복원한다.
UPDATE matching_confirmation_sagas
SET end_time_next_day = (end_time <= start_time)
WHERE seat_reservation_id IS NOT NULL
  AND start_time IS NOT NULL
  AND end_time IS NOT NULL;
