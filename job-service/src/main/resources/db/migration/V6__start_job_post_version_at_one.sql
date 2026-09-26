UPDATE job_posts
SET version = 1
WHERE version = 0;

UPDATE job_application_admissions
SET job_version = 1
WHERE job_version = 0;

ALTER TABLE job_posts
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 1;
