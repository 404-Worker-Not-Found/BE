package com.workernotfound.chat.domain.chat.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.*;

@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long chatRoomId;

  @Column(nullable = false)
  private Long senderMemberId;

  @Column(nullable = false, length = 128)
  private String clientMessageId;

  @Column(nullable = false, length = 2000)
  private String content;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  @Builder
  private ChatMessage(Long chatRoomId, Long senderMemberId, String clientMessageId, String content) {
    this.chatRoomId = chatRoomId;
    this.senderMemberId = senderMemberId;
    this.clientMessageId = clientMessageId;
    this.content = content;
    this.createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
  }
}
