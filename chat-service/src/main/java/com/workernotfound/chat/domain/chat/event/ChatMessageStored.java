package com.workernotfound.chat.domain.chat.event;

public record ChatMessageStored(Long chatRoomId, Long messageId) {}
