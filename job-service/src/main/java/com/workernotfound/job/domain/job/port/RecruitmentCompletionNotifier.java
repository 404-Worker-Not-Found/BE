package com.workernotfound.job.domain.job.port;

/**
 * matching-service에 공고의 모집 완료를 알리는 포트.
 *
 * <p>정상 반환은 상대 서비스가 성공 응답을 준 경우뿐이다. 그 밖의 모든 결과는
 * {@link com.workernotfound.job.domain.job.exception.RecruitmentCompletionNotificationException}으로 알린다.
 */
public interface RecruitmentCompletionNotifier {

    void notifyRecruitmentCompleted(Long jobPostId, Long jobVersion, String commandId);
}
