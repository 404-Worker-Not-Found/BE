package com.workernotfound.chat.domain.chat.dto.response;

import java.util.List;

public record ChatMessageSyncResponse(
    List<ChatMessageResponse> content, Long nextAfterId, boolean hasNext) {}
