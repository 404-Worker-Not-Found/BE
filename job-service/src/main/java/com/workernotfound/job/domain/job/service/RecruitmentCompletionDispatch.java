package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;

// 실행권을 얻은 한 번의 전송 시도. 전송 값은 저장된 명령에서만 읽고 현재 공고를 다시 읽지 않는다.
public record RecruitmentCompletionDispatch(
        Long id,
        String commandId,
        Long jobPostId,
        Long jobVersion,
        int attemptCount,
        String leaseToken
) {

    static RecruitmentCompletionDispatch from(RecruitmentCompletionCommand command) {
        return new RecruitmentCompletionDispatch(
                command.getId(),
                command.getCommandId(),
                command.getJobPostId(),
                command.getJobVersion(),
                command.getAttemptCount(),
                command.getLeaseToken()
        );
    }
}
