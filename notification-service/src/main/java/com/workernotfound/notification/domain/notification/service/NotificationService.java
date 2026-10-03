package com.workernotfound.notification.domain.notification.service;

import com.workernotfound.notification.domain.notification.dto.response.*;
import com.workernotfound.notification.domain.notification.exception.NotificationErrorCode;
import com.workernotfound.notification.domain.notification.repository.NotificationRepository;
import com.workernotfound.notification.global.exception.BusinessException;
import com.workernotfound.notification.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {
  private final NotificationRepository notifications;

  public NotificationPageResponse getNotifications(AuthenticatedMember member, Long beforeId, int size) {
    var result = notifications.findForMember(member.memberId(), member.role(), beforeId, PageRequest.of(0, size + 1));
    var content = result.stream().limit(size).map(NotificationResponse::from).toList();
    boolean hasNext = result.size() > size;
    Long nextBeforeId = hasNext ? content.get(content.size() - 1).notificationId() : null;
    return new NotificationPageResponse(content, nextBeforeId, hasNext);
  }

  public UnreadCountResponse getUnreadCount(AuthenticatedMember member) {
    return new UnreadCountResponse(notifications.countByMemberIdAndMemberRoleAndReadAtIsNull(
        member.memberId(), member.role()));
  }

  @Transactional
  public NotificationResponse markRead(AuthenticatedMember member, Long id) {
    var notification = notifications.findForMemberForUpdate(id, member.memberId(), member.role())
        .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOT_FOUND));
    notification.markRead();
    return NotificationResponse.from(notification);
  }
}
