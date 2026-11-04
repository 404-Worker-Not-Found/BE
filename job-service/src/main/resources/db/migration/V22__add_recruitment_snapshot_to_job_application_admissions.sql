-- 자동 매칭(#154)이 지원 마감 시각과 모집 인원을 알도록 지원 승인 스냅샷에 추가한다.
ALTER TABLE job_application_admissions
    ADD COLUMN application_deadline DATETIME(6) NULL AFTER longitude,
    ADD COLUMN recruit_count        INT         NULL AFTER application_deadline;

-- 승인은 OPEN 공고에만 발급되고 공개 이후의 결제 조건 변경·공고 수정 기능이 없어, 현재 공고 값이 발급 당시 값과 같다.
UPDATE job_application_admissions admission
    JOIN job_posts post ON post.id = admission.job_post_id
SET admission.application_deadline = post.application_deadline,
    admission.recruit_count        = post.recruit_count;

ALTER TABLE job_application_admissions
    MODIFY COLUMN application_deadline DATETIME(6) NOT NULL,
    MODIFY COLUMN recruit_count        INT         NOT NULL;
