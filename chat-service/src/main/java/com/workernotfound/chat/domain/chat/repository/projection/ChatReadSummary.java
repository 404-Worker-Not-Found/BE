package com.workernotfound.chat.domain.chat.repository.projection;

import java.time.LocalDateTime;

public record ChatReadSummary(Long chatRoomId, Long myLastReadMessageId, LocalDateTime myReadAt,
    Long otherLastReadMessageId, LocalDateTime otherReadAt, long unreadCount) {}
