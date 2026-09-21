package com.workernotfound.chat.domain.chat.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChatRoomCommandRepository {
  private final JdbcTemplate jdbc;

  public record Command(String fingerprint, Long chatRoomId) {}

  public Command lockCommand(String key, String fingerprint) {
    jdbc.update(
        "INSERT INTO chat_commands (command_key, fingerprint) VALUES (?, ?) ON DUPLICATE KEY UPDATE"
            + " command_key = command_key",
        key,
        fingerprint);
    return jdbc.queryForObject(
        "SELECT fingerprint, chat_room_id FROM chat_commands WHERE command_key = ? FOR UPDATE",
        (rs, row) -> new Command(rs.getString(1), rs.getObject(2, Long.class)),
        key);
  }

  public void complete(String key, Long chatRoomId) {
    jdbc.update("UPDATE chat_commands SET chat_room_id = ? WHERE command_key = ?", chatRoomId, key);
  }

  public Long lockMatching(Long matchingId) {
    jdbc.update(
        "INSERT INTO chat_matching_slots (matching_id) VALUES (?) ON DUPLICATE KEY UPDATE"
            + " matching_id = matching_id",
        matchingId);
    return jdbc.queryForObject(
        "SELECT active_chat_room_id FROM chat_matching_slots WHERE matching_id = ? FOR UPDATE",
        Long.class,
        matchingId);
  }

  public void activate(Long matchingId, Long chatRoomId) {
    jdbc.update(
        "UPDATE chat_matching_slots SET active_chat_room_id = ? WHERE matching_id = ?",
        chatRoomId,
        matchingId);
  }

  public void release(Long matchingId, Long chatRoomId) {
    jdbc.update(
        "UPDATE chat_matching_slots SET active_chat_room_id = NULL WHERE matching_id = ? AND"
            + " active_chat_room_id = ?",
        matchingId,
        chatRoomId);
  }

  public void history(Long chatRoomId, String previous, String next, String commandKey) {
    jdbc.update(
        "INSERT INTO chat_status_histories (chat_room_id, previous_status, next_status, command_key,"
            + " occurred_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))",
        chatRoomId,
        previous,
        next,
        commandKey);
  }
}
