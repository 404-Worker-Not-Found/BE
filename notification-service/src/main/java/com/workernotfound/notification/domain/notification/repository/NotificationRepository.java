package com.workernotfound.notification.domain.notification.repository;

import com.workernotfound.notification.domain.notification.entity.Notification;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
  boolean existsByEventId(String eventId);
  long countByMemberIdAndMemberRoleAndReadAtIsNull(Long memberId, String memberRole);

  @Query("select n from Notification n where n.memberId = :memberId and n.memberRole = :role "
      + "and (:beforeId is null or n.id < :beforeId) order by n.id desc")
  List<Notification> findForMember(@Param("memberId") Long memberId, @Param("role") String role,
      @Param("beforeId") Long beforeId, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select n from Notification n where n.id = :id and n.memberId = :memberId and n.memberRole = :role")
  Optional<Notification> findForMemberForUpdate(@Param("id") Long id,
      @Param("memberId") Long memberId, @Param("role") String role);
}
