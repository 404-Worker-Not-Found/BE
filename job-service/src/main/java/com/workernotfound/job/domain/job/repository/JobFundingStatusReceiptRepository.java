package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobFundingStatusReceipt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 예치 상태 수신 기록 저장소. 조회는 호출자가 같은 트랜잭션에서 공고 행 잠금을 잡은 뒤에 한다. 같은 공고의 수신은 그 잠금으로
 * 직렬화되므로 앞선 커밋 결과를 본다. 서로 다른 공고에 같은 키를 쓴 동시 요청은 유일 제약이 마지막으로 막는다.
 */
public interface JobFundingStatusReceiptRepository extends JpaRepository<JobFundingStatusReceipt, Long> {

    Optional<JobFundingStatusReceipt> findByIdempotencyKey(String idempotencyKey);

    Optional<JobFundingStatusReceipt> findByOrderIdAndFundingRevision(String orderId, Long fundingRevision);

    List<JobFundingStatusReceipt> findByJobPostIdOrderByIdAsc(Long jobPostId);
}
