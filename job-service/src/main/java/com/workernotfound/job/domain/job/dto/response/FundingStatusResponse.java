package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.JobFundingStatusReceipt;
import com.workernotfound.job.domain.job.entity.enums.FundingSkipReason;
import com.workernotfound.job.domain.job.entity.enums.FundingStatusResult;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;

// 수신 기록에 저장한 값만으로 만든다. 같은 명령의 재요청은 현재 공고를 다시 읽지 않고 처음 응답을 그대로 받는다.
public record FundingStatusResponse(
        Long jobPostId,
        String orderId,
        Long fundingRevision,
        FundingStatusResult result,
        FundingSkipReason skipReason,
        JobStatus jobStatus,
        boolean fundingBlocked
) {
    public static FundingStatusResponse of(JobFundingStatusReceipt receipt) {
        return new FundingStatusResponse(
                receipt.getJobPostId(),
                receipt.getOrderId(),
                receipt.getFundingRevision(),
                receipt.getResult(),
                receipt.getSkipReason(),
                receipt.getJobStatus(),
                receipt.isFundingBlocked()
        );
    }
}
