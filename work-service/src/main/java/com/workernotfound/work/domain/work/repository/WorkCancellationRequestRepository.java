package com.workernotfound.work.domain.work.repository;

import com.workernotfound.work.domain.work.entity.WorkCancellationRequest;
import com.workernotfound.work.domain.work.entity.enums.WorkCancellationStatus;
import jakarta.persistence.LockModeType;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface WorkCancellationRequestRepository extends JpaRepository<WorkCancellationRequest, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<WorkCancellationRequest> findByIdAndWorkId(Long id, Long workId);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<WorkCancellationRequest> findByWorkIdAndStatus(Long workId, WorkCancellationStatus status);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<WorkCancellationRequest> findByWorkIdAndRequesterMemberIdAndRequestKey(Long workId, Long memberId, String key);
  List<WorkCancellationRequest> findTop20ByWorkIdOrderByIdDesc(Long workId);

  @Query("select r.workId from WorkCancellationRequest r, Work w where r.workId = w.id"
      + " and r.status = com.workernotfound.work.domain.work.entity.enums.WorkCancellationStatus.PENDING"
      + " and (w.status <> com.workernotfound.work.domain.work.entity.enums.WorkStatus.SCHEDULED"
      + " or w.checkedInAt is not null or w.workDate < :date or (w.workDate = :date and w.startTime <= :time)) order by r.id")
  List<Long> findExpiryCandidates(@Param("date") LocalDate date, @Param("time") LocalTime time, Pageable pageable);
}
