package com.workernotfound.matching.domain.matching.repository;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 남은 자리가 있는데 제안할 후보가 없는 공고를 공고마다 한 번 기록한다.
@Repository
@RequiredArgsConstructor
public class MatchingCandidateExhaustionRepository {
	private final JdbcTemplate jdbc;

	public boolean exists(Long jobPostId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
			"select exists(select 1 from matching_candidate_exhaustions where job_post_id = ?)", Boolean.class, jobPostId));
	}

	/** 처음 기록했으면 true, 이미 기록된 공고면 false다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean record(Long jobPostId, Long ownerMemberId, int remainingSeats, LocalDateTime exhaustedAt) {
		try {
			jdbc.update("insert into matching_candidate_exhaustions(job_post_id, owner_member_id, remaining_seats, exhausted_at)"
				+ " values (?, ?, ?, ?)", jobPostId, ownerMemberId, remainingSeats, exhaustedAt);
			return true;
		} catch (DuplicateKeyException alreadyRecorded) {
			return false;
		}
	}
}
