CREATE TABLE chat_read_positions (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 chat_room_id BIGINT NOT NULL,
 member_id BIGINT NOT NULL,
 last_read_message_id BIGINT NOT NULL,
 read_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_read_position_room_member (chat_room_id, member_id),
 CONSTRAINT fk_read_position_room FOREIGN KEY (chat_room_id) REFERENCES chat_rooms(id),
 CONSTRAINT fk_read_position_message FOREIGN KEY (last_read_message_id) REFERENCES chat_messages(id)
);
