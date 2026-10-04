package com.workernotfound.job.domain.job.event;

// 모집 완료 알림 명령이 저장된 트랜잭션 안에서 발행한다. 커밋 후 빠른 전송을 깨우는 용도이며 전달을 보장하지 않는다.
public record RecruitmentCompletionCommandCreatedEvent(Long commandId) {
}
