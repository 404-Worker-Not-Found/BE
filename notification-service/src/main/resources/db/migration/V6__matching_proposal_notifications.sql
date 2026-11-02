-- 매칭 제안(알바생, 지원·매칭 단위)과 자동 매칭 후보 소진(점주, 공고 단위) 알림(#154).
-- 제안 알림은 기존 지원 단위 제약을 따른다. 후보 소진 알림만 공고 단위 예외로 추가한다.
ALTER TABLE notifications DROP CHECK ck_notification_subject;
ALTER TABLE notifications ADD CONSTRAINT ck_notification_subject CHECK (
 (application_id IS NOT NULL AND work_id IS NULL) OR
 (application_id IS NULL AND work_id IS NOT NULL) OR
 (notification_type IN ('FUNDING_REVIEW_REQUIRED', 'MatchingCandidatesExhausted') AND member_role='OWNER'
  AND application_id IS NULL AND work_id IS NULL AND matching_id IS NULL));
