ALTER TABLE notifications
 MODIFY application_id BIGINT NULL,
 ADD COLUMN work_id BIGINT,
 ADD CONSTRAINT ck_notification_subject CHECK (
   (application_id IS NOT NULL AND work_id IS NULL) OR
   (application_id IS NULL AND work_id IS NOT NULL));
