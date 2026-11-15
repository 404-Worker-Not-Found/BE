package com.workernotfound.member.domain.review.repository;

import com.workernotfound.member.domain.review.service.WorkNoShow;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 신뢰 점수에 반영한 노쇼 근무. 근무 ID가 기본 키라 같은 근무는 한 번만 감점한다.
@Repository
@RequiredArgsConstructor
public class MemberNoShowWorkRepository {
	private final JdbcTemplate jdbc;

	public Optional<WorkNoShow> find(Long workId) {
		return jdbc.query("select event_id, work_id, job_post_id, owner_member_id, worker_member_id, work_date, no_show_at"
				+ " from member_no_show_works where work_id = ?",
			(rs, row) -> new WorkNoShow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
				rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDateTime.class)), workId)
			.stream().findFirst();
	}

	// 같은 근무가 동시에 들어오면 한쪽은 기본 키 충돌로 실패해 트랜잭션 전체가 롤백된다.
	public void insert(WorkNoShow noShow, LocalDateTime receivedAt) {
		jdbc.update("insert into member_no_show_works(work_id, event_id, job_post_id, owner_member_id, worker_member_id,"
				+ " work_date, no_show_at, received_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
			noShow.workId(), noShow.eventId(), noShow.jobPostId(), noShow.ownerMemberId(), noShow.workerMemberId(),
			noShow.workDate() == null ? null : Date.valueOf(noShow.workDate()), noShow.noShowAt(), receivedAt);
	}
}
