package com.workernotfound.job.domain.job.repository;

// 공고별 APPLIED 지원 수 집계 결과
public interface JobApplicantCount {

    Long getJobPostId();

    Long getApplicantCount();
}
