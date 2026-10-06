-- 기존 예약의 발급 당시 위치는 복원할 수 없으므로 현재 공고 좌표로 채우지 않는다.
ALTER TABLE job_matching_seat_reservations
    ADD COLUMN latitude DECIMAL(10,7) NULL,
    ADD COLUMN longitude DECIMAL(10,7) NULL,
    ADD CONSTRAINT ck_seat_reservation_location_pair CHECK (
        (latitude IS NULL AND longitude IS NULL) OR
        (latitude IS NOT NULL AND longitude IS NOT NULL
            AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
    );
