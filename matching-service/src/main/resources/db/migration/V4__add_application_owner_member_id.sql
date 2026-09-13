ALTER TABLE applications
    ADD COLUMN owner_member_id BIGINT NULL AFTER worker_member_id,
    ADD INDEX idx_applications_owner_job_status (owner_member_id, job_post_id, status);
