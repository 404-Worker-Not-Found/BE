package com.workernotfound.job.domain.job.port;

import java.time.Duration;

/**
 * matching-service에 공고의 모집 완료를 알리는 포트.
 *
 * <p>정상 반환은 상대 서비스가 성공 응답을 준 경우뿐이다. 그 밖의 모든 결과는
 * {@link com.workernotfound.job.domain.job.exception.RecruitmentCompletionNotificationException}으로 알린다.
 */
public interface RecruitmentCompletionNotifier {

    void notifyRecruitmentCompleted(Long jobPostId, Long jobVersion, String commandId);

    // 한 번의 호출이 응답을 기다릴 수 있는 최대 시간(연결·응답 타임아웃의 합)
    Duration maxCallDuration();
}
