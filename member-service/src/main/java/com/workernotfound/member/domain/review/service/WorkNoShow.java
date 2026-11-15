package com.workernotfound.member.domain.review.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

// 근로자가 확정 근무에 출근하지 않아 자동 노쇼 처리된 사실. noShowAt은 근무 일정 시간대의 로컬 시각이다.
public record WorkNoShow(String eventId, Long workId, Long jobPostId, Long ownerMemberId, Long workerMemberId,
	LocalDate workDate, LocalDateTime noShowAt) {

	// 같은 근무의 재전달인지 판단한다. 이벤트 ID와 처리 시각은 재발행에서 달라질 수 있어 비교하지 않는다.
	public boolean describesSameWork(WorkNoShow other) {
		return workId.equals(other.workId) && jobPostId.equals(other.jobPostId)
			&& ownerMemberId.equals(other.ownerMemberId) && workerMemberId.equals(other.workerMemberId)
			&& Objects.equals(workDate, other.workDate);
	}
}
