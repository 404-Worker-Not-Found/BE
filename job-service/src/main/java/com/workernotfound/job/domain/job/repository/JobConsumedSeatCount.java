package com.workernotfound.job.domain.job.repository;

// 공고별 확정(CONSUMED) 자리 예약 수 집계 결과
public interface JobConsumedSeatCount {

    Long getJobPostId();

    Long getConsumedCount();
}
