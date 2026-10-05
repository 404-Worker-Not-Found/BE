package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobPaymentRefundReview;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 환불 검토 대상 저장소. 같은 공고의 생성은 예치 상태 수신의 공고 행 잠금 아래에서만 한다.
public interface JobPaymentRefundReviewRepository extends JpaRepository<JobPaymentRefundReview, Long> {

    boolean existsByOrderId(String orderId);

    Optional<JobPaymentRefundReview> findByOrderId(String orderId);

    List<JobPaymentRefundReview> findByJobPostIdOrderByIdAsc(Long jobPostId);
}
