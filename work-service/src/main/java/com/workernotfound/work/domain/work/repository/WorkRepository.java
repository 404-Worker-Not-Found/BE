package com.workernotfound.work.domain.work.repository;

import com.workernotfound.work.domain.work.entity.Work;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkRepository extends JpaRepository<Work, Long> {
  @Query("select w.id from Work w where w.status = com.workernotfound.work.domain.work.entity.enums.WorkStatus.SCHEDULED"
      + " and w.confirmedAt is not null and w.checkedInAt is null"
      + " and w.latitude is not null and w.longitude is not null"
      + " and (w.workDate < :date or (w.workDate = :date and w.startTime <= :time))"
      + " order by w.workDate, w.startTime, w.id")
  java.util.List<Long> findNoShowCandidates(@Param("date") java.time.LocalDate date,
      @Param("time") java.time.LocalTime time, org.springframework.data.domain.Pageable pageable);

  @Query(
      "select w from Work w where w.confirmedAt is not null and "
          + "((:role = 'OWNER' and w.ownerMemberId = :memberId) or "
          + "(:role = 'WORKER' and w.workerMemberId = :memberId))")
  org.springframework.data.domain.Page<Work> findConfirmedByMember(
      @Param("memberId") Long memberId,
      @Param("role") String role,
      org.springframework.data.domain.Pageable pageable);

  @Query(
      "select w from Work w where w.id = :id and w.confirmedAt is not null and "
          + "((:role = 'OWNER' and w.ownerMemberId = :memberId) or "
          + "(:role = 'WORKER' and w.workerMemberId = :memberId))")
  Optional<Work> findConfirmedByIdAndMember(
      @Param("id") Long id, @Param("memberId") Long memberId, @Param("role") String role);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from Work w where w.id = :id and w.confirmedAt is not null and "
      + "((:role = 'OWNER' and w.ownerMemberId = :memberId) or "
      + "(:role = 'WORKER' and w.workerMemberId = :memberId))")
  Optional<Work> findConfirmedForUpdate(@Param("id") Long id,
      @Param("memberId") Long memberId, @Param("role") String role);

  @Query("select w.matchingId from Work w where w.id = :id")
  Optional<Long> findMatchingIdById(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from Work w where w.id = :id")
  Optional<Work> findByIdForUpdate(@Param("id") Long id);
}
