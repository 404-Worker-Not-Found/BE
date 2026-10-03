ALTER TABLE job_application_admissions
    ADD COLUMN owner_member_id BIGINT         NULL AFTER job_version,
    ADD COLUMN category_id     BIGINT         NULL AFTER owner_member_id,
    ADD COLUMN work_date       DATE           NULL AFTER category_id,
    ADD COLUMN start_time      TIME           NULL AFTER work_date,
    ADD COLUMN end_time        TIME           NULL AFTER start_time,
    ADD COLUMN latitude        DECIMAL(10, 7) NULL AFTER end_time,
    ADD COLUMN longitude       DECIMAL(10, 7) NULL AFTER latitude;

-- 기존 승인은 발급 당시 값을 보관하지 않는다. 공고 수정 기능이 아직 없어 현재 공고 값이 발급 당시 값과 같다.
UPDATE job_application_admissions admission
    JOIN job_posts post ON post.id = admission.job_post_id
SET admission.owner_member_id = post.owner_id,
    admission.category_id     = post.category_id,
    admission.work_date       = post.work_date,
    admission.start_time      = post.start_time,
    admission.end_time        = post.end_time,
    admission.latitude        = post.latitude,
    admission.longitude       = post.longitude;

ALTER TABLE job_application_admissions
    MODIFY COLUMN owner_member_id BIGINT         NOT NULL,
    MODIFY COLUMN category_id     BIGINT         NOT NULL,
    MODIFY COLUMN work_date       DATE           NOT NULL,
    MODIFY COLUMN start_time      TIME           NOT NULL,
    MODIFY COLUMN end_time        TIME           NOT NULL,
    MODIFY COLUMN latitude        DECIMAL(10, 7) NOT NULL,
    MODIFY COLUMN longitude       DECIMAL(10, 7) NOT NULL;
