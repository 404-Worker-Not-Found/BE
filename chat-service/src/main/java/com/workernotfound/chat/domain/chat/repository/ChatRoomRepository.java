package com.workernotfound.chat.domain.chat.repository;

import com.workernotfound.chat.domain.chat.entity.ChatRoom;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
  @Query("select w.matchingId from ChatRoom w where w.id = :id")
  Optional<Long> findMatchingIdById(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from ChatRoom w where w.id = :id")
  Optional<ChatRoom> findByIdForUpdate(@Param("id") Long id);
}
