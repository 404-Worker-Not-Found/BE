package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobPaymentFunding;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 주문별 예치 상태 저장소. 생성·갱신은 공고 행 잠금 아래에서만 한다.
public interface JobPaymentFundingRepository extends JpaRepository<JobPaymentFunding, Long> {

    Optional<JobPaymentFunding> findByOrderId(String orderId);
}
