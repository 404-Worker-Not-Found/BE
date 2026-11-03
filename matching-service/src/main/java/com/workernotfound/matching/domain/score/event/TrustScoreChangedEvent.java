package com.workernotfound.matching.domain.score.event;

// 회원 신뢰 점수 사본이 바뀐 뒤 그 회원이 지원 중인 공고의 점수 대기열을 다시 맞추기 위한 로컬 이벤트.
public record TrustScoreChangedEvent(Long memberId) {
}
