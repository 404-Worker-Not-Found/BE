package com.workernotfound.member.domain.review.repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 회원이 받은 리뷰 수와 평점 합. 동시에 쓰는 리뷰도 한 문장으로 더해 잃지 않는다.
@Repository
@RequiredArgsConstructor
public class MemberReviewStatsRepository {
	private final JdbcTemplate jdbc;

	public void add(Long memberId, int rating, LocalDateTime now) {
		jdbc.update("insert into member_review_stats(member_id, review_count, rating_sum, updated_at) values (?, 1, ?, ?)"
			+ " on duplicate key update review_count = review_count + 1, rating_sum = rating_sum + ?, updated_at = ?",
			memberId, rating, now, rating, now);
	}

	public Summary find(Long memberId) {
		return jdbc.query("select review_count, rating_sum from member_review_stats where member_id = ?",
				rs -> rs.next() ? Summary.of(rs.getLong(1), rs.getLong(2)) : Summary.EMPTY, memberId);
	}

	// 평균은 소수 첫째 자리까지 반올림한다. 받은 리뷰가 없으면 null이다.
	public record Summary(long reviewCount, BigDecimal averageRating) {
		static final Summary EMPTY = new Summary(0, null);

		static Summary of(long count, long sum) {
			if (count == 0) return EMPTY;
			return new Summary(count, BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP));
		}
	}
}
