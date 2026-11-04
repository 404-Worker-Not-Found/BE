package com.workernotfound.job.domain.job.entity.enums;

// matching-service 지원 상태. 지원자 수 projection이 지원별 현재 상태로 보관하며 APPLIED만 지원자 수로 센다.
public enum ApplicationStatus {
    APPLIED,
    CANCELED,
    SELECTED,
    REJECTED
}
