ALTER TABLE works
 ADD COLUMN latitude DECIMAL(10,7),
 ADD COLUMN longitude DECIMAL(10,7),
 ADD COLUMN checked_in_at DATETIME(6),
 ADD COLUMN started_at DATETIME(6),
 ADD COLUMN completed_at DATETIME(6),
 ADD COLUMN check_in_distance_meters DOUBLE,
 ADD COLUMN attendance_policy VARCHAR(64),
 ADD CONSTRAINT ck_work_location_pair CHECK ((latitude IS NULL AND longitude IS NULL) OR
   (latitude IS NOT NULL AND longitude IS NOT NULL AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180));
ALTER TABLE work_status_histories
 MODIFY command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN actor_member_id BIGINT,
 ADD CONSTRAINT ck_work_history_actor CHECK ((command_key IS NOT NULL AND actor_member_id IS NULL) OR
   (command_key IS NULL AND actor_member_id IS NOT NULL));
