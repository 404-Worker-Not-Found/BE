package com.workernotfound.notification.domain.notification.dto.response;

import java.util.List;

public record NotificationPageResponse(List<NotificationResponse> content, Long nextBeforeId, boolean hasNext) {}
