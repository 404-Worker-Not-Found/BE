ALTER TABLE job_posts
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD INDEX idx_job_posts_owner_deleted_created (owner_id, deleted_at, created_at DESC, id DESC);
