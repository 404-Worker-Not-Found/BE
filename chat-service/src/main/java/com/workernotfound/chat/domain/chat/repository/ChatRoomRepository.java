package com.workernotfound.chat.domain.chat.repository;

import com.workernotfound.chat.domain.chat.entity.ChatRoom;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
  @Query(
      "select r from ChatRoom r where r.status = 'OPEN' and r.confirmedAt is not null and "
          + "((:role = 'OWNER' and r.ownerMemberId = :memberId) or "
          + "(:role = 'WORKER' and r.workerMemberId = :memberId))")
  Page<ChatRoom> findConfirmedForMember(
      @Param("memberId") Long memberId, @Param("role") String role, Pageable pageable);

  @Query(
      "select r from ChatRoom r where r.id = :id and r.status = 'OPEN' and "
          + "r.confirmedAt is not null and "
          + "((:role = 'OWNER' and r.ownerMemberId = :memberId) or "
          + "(:role = 'WORKER' and r.workerMemberId = :memberId))")
  Optional<ChatRoom> findConfirmedForMember(
      @Param("id") Long id, @Param("memberId") Long memberId, @Param("role") String role);

  @Query("select w.matchingId from ChatRoom w where w.id = :id")
  Optional<Long> findMatchingIdById(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from ChatRoom w where w.id = :id")
  Optional<ChatRoom> findByIdForUpdate(@Param("id") Long id);
}
