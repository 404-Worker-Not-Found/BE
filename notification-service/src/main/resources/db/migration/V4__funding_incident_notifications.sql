-- 기존 지원/근무 알림 제약을 유지하면서 점주의 공고 단위 예치 이상 알림만 추가한다.
ALTER TABLE notifications DROP CHECK ck_notification_subject;
ALTER TABLE notifications ADD CONSTRAINT ck_notification_subject CHECK (
 (application_id IS NOT NULL AND work_id IS NULL) OR
 (application_id IS NULL AND work_id IS NOT NULL) OR
 (notification_type='FUNDING_REVIEW_REQUIRED' AND member_role='OWNER'
  AND application_id IS NULL AND work_id IS NULL AND matching_id IS NULL));
