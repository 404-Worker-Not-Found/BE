package com.workernotfound.job.domain.job.entity.enums;

// 점주 수동 마감 요청의 처리 결과
public enum JobCloseResult {
    // 이 요청이 공고를 마감했다.
    CLOSED,
    // 요청을 처리할 때 이미 마감된 공고였다. 상태·이력·알림을 바꾸지 않았다.
    ALREADY_CLOSED
}
