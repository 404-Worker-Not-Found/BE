package com.workernotfound.chat.domain.chat.repository;

import com.workernotfound.chat.domain.chat.entity.ChatReadPosition;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatReadPositionRepository extends JpaRepository<ChatReadPosition, Long> {
  Optional<ChatReadPosition> findByChatRoomIdAndMemberId(Long chatRoomId, Long memberId);
}
