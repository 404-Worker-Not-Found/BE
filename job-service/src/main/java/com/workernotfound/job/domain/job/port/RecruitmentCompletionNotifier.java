package com.workernotfound.job.domain.job.port;

import java.time.Duration;

/**
 * matching-service에 공고의 모집 완료를 알리는 포트.
 *
 * <p>정상 반환은 상대 서비스가 성공 응답을 준 경우뿐이다. 상대 서비스의 실패 응답과 통신 실패(연결 오류, 제한시간 초과,
 * 교환 취소·중단)는 {@link com.workernotfound.job.domain.job.exception.RecruitmentCompletionNotificationException}으로
 * 알린다. 구현 내부의 예상하지 못한 오류는 이 예외로 바꾸지 않고 다른 런타임 예외로 그대로 전파한다.
 */
public interface RecruitmentCompletionNotifier {

    void notifyRecruitmentCompleted(Long jobPostId, Long jobVersion, String commandId);

    /**
     * 한 번의 호출에 실제로 강제되는 전체 제한시간. 연결 시작부터 응답 본문 수신 완료까지를 포함한다.
     *
     * <p>이 시간을 넘기면 교환을 취소해 연결을 닫고 {@code TIMEOUT} 실패로 알린다. 리스 길이는 이 값을 기준으로 검증한다.
     */
    Duration maxCallDuration();
}
