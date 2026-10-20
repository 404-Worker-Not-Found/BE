package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobApplicationAdmissionRepository extends JpaRepository<JobApplicationAdmission, Long> {

    Optional<JobApplicationAdmission> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from JobApplicationAdmission a where a.id = :id")
    Optional<JobApplicationAdmission> findByIdForUpdate(@Param("id") Long id);

    // 결제 조건을 바꿀 수 있는지 확인한다. 지원 승인이 한 번이라도 발급된 공고는 이미 공개된 적이 있다.
    boolean existsByJobPostId(Long jobPostId);

    @Query("select coalesce(max(a.id), 0) from JobApplicationAdmission a")
    long findMaxId();

    // 순회 시작 때 고정한 upperId까지만 조회해 새 승인이 계속 들어와도 기존 실패 건을 다시 찾는다.
    @Query("""
            select a.id from JobApplicationAdmission a
            where a.status = com.workernotfound.job.domain.job.entity.enums.ApplicationAdmissionStatus.RESERVED
              and a.expiresAt <= :now
              and a.id > :afterId
              and a.id <= :upperId
            order by a.id
            """)
    List<Long> findOverdueReservedIdsAfter(
            @Param("now") LocalDateTime now,
            @Param("afterId") Long afterId,
            @Param("upperId") Long upperId,
            Pageable pageable
    );
}
