package com.workernotfound.chat.domain.chat.dto.response;

import java.util.List;

public record ChatRoomPageResponse(
    List<ChatRoomDetailResponse> content, int page, int size, long totalElements, int totalPages) {}
