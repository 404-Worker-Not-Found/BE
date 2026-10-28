package com.workernotfound.member.domain.review.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 리뷰 대상이 되는 근무 완료 사실. completedAt은 근무 일정 시간대의 로컬 시각이다.
public record WorkCompletion(String eventId, Long workId, Long jobPostId, Long ownerMemberId, Long workerMemberId,
	LocalDate workDate, LocalDateTime completedAt) {
}
