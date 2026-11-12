-- 근무 완료 이벤트 하나로 상대방 완료 알림과 양측 리뷰 작성 요청 알림을 함께 저장한다(#173).
-- 같은 이벤트·수신자에 알림 종류가 둘이 될 수 있으므로 중복 방지 키에 알림 종류를 더한다.
-- 새 키도 event_id로 시작하므로 notification_events 외래 키 인덱스를 대신한다. 새 키를 먼저 만든 뒤 기존 키를 지운다.
ALTER TABLE notifications
 ADD UNIQUE KEY uk_notification_recipient_type (event_id, member_id, member_role, notification_type);
ALTER TABLE notifications DROP INDEX uk_notification_recipient;
