package com.workernotfound.job.domain.job.port;

import java.util.List;

/**
 * matching-service에서 공고별 지원 상태 스냅샷을 읽는 포트.
 *
 * <p>공고의 모든 지원(상태 무관)을 돌려준다. 지원이 없으면 빈 목록이다. 통신 실패, 실패 응답, 계약과 다른 응답은
 * {@link com.workernotfound.job.domain.job.exception.ApplicationStateSnapshotException}으로 알린다.
 */
public interface ApplicationStateSnapshotReader {

    List<ApplicationStateSnapshot> readApplicationStates(Long jobPostId);
}
