package com.workernotfound.chat.domain.chat.repository;

import com.workernotfound.chat.domain.chat.entity.ChatRoom;
import com.workernotfound.chat.domain.chat.repository.projection.ChatReadSummary;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ChatReadSummaryRepository extends Repository<ChatRoom, Long> {
  // 양쪽 읽음 위치와 미읽음 수를 같은 SELECT에서 읽어 서로 다른 시점의 상태가 섞이지 않게 한다.
  @Query("""
      select new com.workernotfound.chat.domain.chat.repository.projection.ChatReadSummary(
          r.id, mine.lastReadMessageId, mine.readAt,
          other.lastReadMessageId, other.readAt, count(message.id))
      from ChatRoom r
      left join ChatReadPosition mine on mine.chatRoomId = r.id and mine.memberId = :memberId
      left join ChatReadPosition other on other.chatRoomId = r.id
          and other.memberId = case when r.ownerMemberId = :memberId
              then r.workerMemberId else r.ownerMemberId end
      left join ChatMessage message on message.chatRoomId = r.id
          and message.senderMemberId <> :memberId
          and message.senderMemberId = case when r.ownerMemberId = :memberId
              then r.workerMemberId else r.ownerMemberId end
          and message.id > coalesce(mine.lastReadMessageId, 0)
      where r.id in :roomIds and (r.ownerMemberId = :memberId or r.workerMemberId = :memberId)
      group by r.id, mine.lastReadMessageId, mine.readAt, other.lastReadMessageId, other.readAt
      """)
  List<ChatReadSummary> findByRoomIdsAndMemberId(
      @Param("roomIds") List<Long> roomIds, @Param("memberId") Long memberId);
}
