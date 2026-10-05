package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPaymentChangeRequest;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;

// 결제 조건 변경·재결제 요청과 그 요청이 발급한 주문 생성 명령. 명령은 결제 스냅샷(버전·금액·통화)과 연결된 주문 ID를 담는다.
public record JobPaymentChange(JobPaymentChangeRequest request, JobPaymentOrderCommand command) {
}
