ALTER TABLE job_posts
    ADD COLUMN address VARCHAR(255) NOT NULL DEFAULT '' AFTER store_name;
