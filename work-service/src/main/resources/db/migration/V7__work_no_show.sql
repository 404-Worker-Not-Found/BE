ALTER TABLE works
 ADD COLUMN no_show_at DATETIME(6),
 ADD COLUMN no_show_deadline DATETIME(6),
 ADD INDEX idx_work_no_show_candidates (status, work_date, start_time, id);

ALTER TABLE work_status_histories
 DROP CHECK ck_work_history_actor,
 ADD COLUMN system_actor BOOLEAN NOT NULL DEFAULT FALSE,
 ADD CONSTRAINT ck_work_history_actor CHECK (
   (system_actor = TRUE AND command_key IS NULL AND actor_member_id IS NULL
     AND previous_status IS NOT NULL AND previous_status = 'SCHEDULED' AND next_status = 'NO_SHOW')
   OR (system_actor = FALSE AND (
     (command_key IS NOT NULL AND actor_member_id IS NULL)
     OR (command_key IS NULL AND actor_member_id IS NOT NULL))));
