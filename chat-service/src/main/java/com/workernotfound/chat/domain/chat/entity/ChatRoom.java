package com.workernotfound.chat.domain.chat.entity;

import com.workernotfound.chat.domain.chat.entity.enums.ChatRoomStatus;
import com.workernotfound.chat.domain.chat.exception.ChatRoomErrorCode;
import com.workernotfound.chat.global.exception.BusinessException;
import jakarta.persistence.*;
import java.time.*;
import lombok.*;

@Entity
@Table(name = "chat_rooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long matchingId;

  @Column(nullable = false)
  private Long jobPostId;

  @Column(nullable = false)
  private Long ownerMemberId;

  @Column(nullable = false)
  private Long workerMemberId;

  @Column(nullable = false)
  private String workId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ChatRoomStatus status;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  private LocalDateTime closedAt;
  private LocalDateTime confirmedAt;

  @Column(nullable = false)
  private long confirmationRevision;
  @Version private Long version;

  @Builder
  private ChatRoom(
      Long matchingId,
      Long jobPostId,
      Long ownerMemberId,
      Long workerMemberId,
      String workId) {
    this.matchingId = matchingId;
    this.jobPostId = jobPostId;
    this.ownerMemberId = ownerMemberId;
    this.workerMemberId = workerMemberId;
    this.workId = workId;
    this.status = ChatRoomStatus.OPEN;
    this.createdAt = LocalDateTime.now();
  }

  public boolean close() {
    if (status == ChatRoomStatus.CLOSED) return false;
    if (confirmedAt != null)
      throw new BusinessException(ChatRoomErrorCode.CONFIRMED_CHAT_CANNOT_CLOSE);
    status = ChatRoomStatus.CLOSED;
    closedAt = LocalDateTime.now();
    return true;
  }

  public void confirm(long revision, LocalDateTime occurredAt) {
    if (revision <= confirmationRevision || status == ChatRoomStatus.CLOSED) return;
    if (confirmedAt == null) confirmedAt = occurredAt;
    confirmationRevision = revision;
  }
}
