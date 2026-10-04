CREATE TABLE chat_messages (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 chat_room_id BIGINT NOT NULL,
 sender_member_id BIGINT NOT NULL,
 client_message_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_message_room FOREIGN KEY (chat_room_id) REFERENCES chat_rooms(id),
 UNIQUE KEY uk_message_client (chat_room_id, sender_member_id, client_message_id),
 KEY idx_message_room_id (chat_room_id, id)
);
