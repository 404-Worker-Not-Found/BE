package com.workernotfound.matching.domain.score.repository;

import com.workernotfound.matching.domain.score.model.TrustScoreUpdate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 회원 신뢰 점수 사본. 회원별로 더 큰 revision만 반영해 중복·역순 전달을 거른다.
@Repository
@RequiredArgsConstructor
public class MemberTrustScoreRepository {
	private final NamedParameterJdbcTemplate jdbc;

	/** 더 큰 revision이면 반영하고 true를 돌려준다. 같은 revision의 재전달과 더 작은 revision은 false다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean apply(TrustScoreUpdate update, LocalDateTime receivedAt) {
		MapSqlParameterSource parameters = new MapSqlParameterSource()
			.addValue("memberId", update.memberId())
			.addValue("trustScore", update.trustScore())
			.addValue("reviewCount", update.reviewCount())
			.addValue("policyVersion", update.policyVersion())
			.addValue("revision", update.revision())
			.addValue("eventId", update.eventId())
			.addValue("receivedAt", receivedAt);
		try {
			jdbc.update("""
				insert into member_trust_scores
					(member_id, trust_score, review_count, policy_version, revision, event_id, received_at)
				values (:memberId, :trustScore, :reviewCount, :policyVersion, :revision, :eventId, :receivedAt)
				""", parameters);
			return true;
		} catch (DuplicateKeyException alreadyReceived) {
			// 이미 받은 회원이다. 아래 조건부 갱신이 더 큰 revision만 반영한다.
		}
		return jdbc.update("""
			update member_trust_scores
			set trust_score = :trustScore, review_count = :reviewCount, policy_version = :policyVersion,
				revision = :revision, event_id = :eventId, received_at = :receivedAt
			where member_id = :memberId and revision < :revision
			""", parameters) > 0;
	}

	public Map<Long, BigDecimal> findTrustScores(Collection<Long> memberIds) {
		Map<Long, BigDecimal> trustScores = new HashMap<>();
		if (memberIds.isEmpty()) {
			return trustScores;
		}
		jdbc.query(
			"select member_id, trust_score from member_trust_scores where member_id in (:memberIds)",
			new MapSqlParameterSource("memberIds", memberIds),
			resultSet -> {
				trustScores.put(resultSet.getLong("member_id"), resultSet.getBigDecimal("trust_score"));
			}
		);
		return trustScores;
	}
}
