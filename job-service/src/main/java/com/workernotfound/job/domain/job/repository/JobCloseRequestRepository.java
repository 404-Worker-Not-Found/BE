package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobCloseRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 점주 수동 마감 요청 저장소. 조회와 생성은 공고 행 잠금 아래에서 한다. 서로 다른 공고에 같은 키를 쓴 동시 요청은 유일 제약이 막는다.
 */
public interface JobCloseRequestRepository extends JpaRepository<JobCloseRequest, Long> {

    Optional<JobCloseRequest> findByIdempotencyKey(String idempotencyKey);
}
