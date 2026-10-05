package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobPaymentFunding;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 주문별 예치 상태 저장소. 생성·갱신은 공고 행 잠금 아래에서만 한다.
public interface JobPaymentFundingRepository extends JpaRepository<JobPaymentFunding, Long> {

    Optional<JobPaymentFunding> findByOrderId(String orderId);

    /**
     * 커밋된 최신 예치 상태를 잠금 조회(FOR SHARE)로 읽는다. 공고 행 잠금 전에 일반 조회로 읽기 스냅샷을 만든 트랜잭션(주문 연결)이
     * 잠금을 기다리는 동안 커밋된 예치 반영을 놓치지 않게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select f from JobPaymentFunding f where f.orderId = :orderId")
    Optional<JobPaymentFunding> findCommittedByOrderId(@Param("orderId") String orderId);
}
