package com.workernotfound.member.domain.review.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 회원별 현재 신뢰 점수. 변경마다 revision을 1 올려 이벤트 소비자가 역순·중복 반영을 거른다.
@Repository
@RequiredArgsConstructor
public class MemberTrustScoreRepository {
	private final JdbcTemplate jdbc;

	/** 신뢰 점수를 저장하고 이번 변경의 revision을 돌려준다. 호출자가 같은 회원의 평점 집계 행을 잠근 상태여야 한다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public long save(Long memberId, BigDecimal trustScore, long reviewCount, String policyVersion, LocalDateTime now) {
		jdbc.update("insert into member_trust_scores(member_id, trust_score, review_count, policy_version, revision, updated_at)"
				+ " values (?, ?, ?, ?, 1, ?) on duplicate key update trust_score = ?, review_count = ?, policy_version = ?,"
				+ " revision = revision + 1, updated_at = ?",
			memberId, trustScore, reviewCount, policyVersion, now, trustScore, reviewCount, policyVersion, now);
		return jdbc.queryForObject("select revision from member_trust_scores where member_id = ?", Long.class, memberId);
	}

	public Optional<CurrentTrustScore> find(Long memberId) {
		return jdbc.query("select trust_score, review_count, revision from member_trust_scores where member_id = ?",
			(rs, row) -> new CurrentTrustScore(rs.getBigDecimal(1), rs.getLong(2), rs.getLong(3)), memberId).stream().findFirst();
	}

	public record CurrentTrustScore(BigDecimal trustScore, long reviewCount, long revision) {
	}
}
