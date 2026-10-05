package com.workernotfound.job.domain.job.entity.enums;

public enum JobStatus {
    // 결제 예치 전 비공개 상태. 점주 본인만 조회할 수 있고 검색·지원 승인·자리 예약 대상이 아니다.
    PAYMENT_PENDING,
    OPEN,
    MATCHING,
    CLOSED
}
