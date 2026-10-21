-- 방과 상대 발신자로 범위를 좁힌 뒤 읽음 위치 이후의 메시지 ID를 집계한다.
CREATE INDEX idx_message_room_sender_id ON chat_messages (chat_room_id, sender_member_id, id);
