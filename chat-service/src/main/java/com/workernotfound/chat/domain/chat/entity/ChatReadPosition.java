package com.workernotfound.chat.domain.chat.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.*;

@Entity
@Table(name = "chat_read_positions", uniqueConstraints =
    @UniqueConstraint(name = "uk_read_position_room_member", columnNames = {"chat_room_id", "member_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatReadPosition {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long chatRoomId;

  @Column(nullable = false)
  private Long memberId;

  @Column(nullable = false)
  private Long lastReadMessageId;

  @Column(nullable = false)
  private LocalDateTime readAt;

  @Builder
  private ChatReadPosition(Long chatRoomId, Long memberId, Long lastReadMessageId) {
    this.chatRoomId = chatRoomId;
    this.memberId = memberId;
    this.lastReadMessageId = lastReadMessageId;
    this.readAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
  }

  // 지연 요청과 재전송은 읽음 위치뿐 아니라 마지막 갱신 시각도 바꾸지 않는다.
  public void advanceTo(long messageId) {
    if (messageId <= lastReadMessageId) return;
    lastReadMessageId = messageId;
    readAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
  }
}
