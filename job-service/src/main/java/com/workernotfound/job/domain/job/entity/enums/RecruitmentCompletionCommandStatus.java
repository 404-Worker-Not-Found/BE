package com.workernotfound.job.domain.job.entity.enums;

// 모집 완료 알림 명령의 전송 상태. 재시도 한도로 명령을 종료하지 않으므로 실패 종결 상태는 두지 않는다.
public enum RecruitmentCompletionCommandStatus {
    PENDING,
    SUCCEEDED
}
