package com.workernotfound.chat.domain.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatReadPositionTests {
  @Test
  void advancesToNewerMessagesAndKeepsMicrosecondPrecision() {
    var position = ChatReadPosition.builder()
        .chatRoomId(1L).memberId(2L).lastReadMessageId(10L).build();
    var firstReadAt = position.getReadAt();

    position.advanceTo(20L);

    assertThat(position.getLastReadMessageId()).isEqualTo(20L);
    assertThat(position.getReadAt()).isAfterOrEqualTo(firstReadAt);
    assertThat(position.getReadAt().getNano() % 1_000).isZero();
  }

  @Test
  void repeatedAndOlderRequestsKeepPositionAndTimestamp() {
    var position = ChatReadPosition.builder()
        .chatRoomId(1L).memberId(2L).lastReadMessageId(20L).build();
    var readAt = position.getReadAt();

    position.advanceTo(20L);
    position.advanceTo(10L);

    assertThat(position.getLastReadMessageId()).isEqualTo(20L);
    assertThat(position.getReadAt()).isEqualTo(readAt);
  }
}
