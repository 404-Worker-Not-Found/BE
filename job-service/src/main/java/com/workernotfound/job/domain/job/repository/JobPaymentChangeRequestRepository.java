package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobPaymentChangeRequest;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 결제 조건 변경·재결제 요청 저장소. 생성과 상태 변경은 공고 행 잠금 아래에서 한다(공고 행 → 명령 행 → 요청 행 순서).
 * 서로 다른 공고에 같은 키를 쓴 동시 요청은 유일 제약이 마지막으로 막는다.
 */
public interface JobPaymentChangeRequestRepository extends JpaRepository<JobPaymentChangeRequest, Long> {

    Optional<JobPaymentChangeRequest> findByIdempotencyKey(String idempotencyKey);

    // 명령과 요청의 연결은 생성 후 바뀌지 않으므로 잠그지 않고 읽어도 된다.
    boolean existsByCommandId(Long commandId);

    Optional<JobPaymentChangeRequest> findFirstByJobPostIdOrderByIdDesc(Long jobPostId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from JobPaymentChangeRequest r where r.commandId = :commandId")
    Optional<JobPaymentChangeRequest> findByCommandIdForUpdate(@Param("commandId") Long commandId);
}
