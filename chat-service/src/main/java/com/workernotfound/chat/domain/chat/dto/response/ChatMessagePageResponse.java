package com.workernotfound.chat.domain.chat.dto.response;

import java.util.List;

public record ChatMessagePageResponse(
    List<ChatMessageResponse> content, Long nextBeforeId, boolean hasNext) {}
