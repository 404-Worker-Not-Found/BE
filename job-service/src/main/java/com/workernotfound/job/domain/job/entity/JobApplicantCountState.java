package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공고별 지원자 수 projection 적재 상태. 같은 공고의 이벤트 반영과 스냅샷 반영은 이 행을 잠가 직렬화한다.
 *
 * <p>{@code syncedAt}이 없으면 초기 적재 전이므로 지원자 수를 보여 주지 않는다. {@code changedAt}이 {@code syncedAt}보다 늦으면
 * 마지막 비교 뒤 이벤트로 바뀐 공고이므로 다음 정합성 비교 대상이다. 행은 저장소의 {@code ensureExists}로만 만든다.
 */
@Getter
@Entity
@Table(name = "job_applicant_count_states")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobApplicantCountState extends BaseTimeEntity {

    @Id
    @Column(name = "job_post_id")
    private Long jobPostId;

    // 마지막으로 반영한 스냅샷의 조회 시작 시각. 이 시각 전에 커밋된 지원 변경은 모두 반영되어 있다.
    private LocalDateTime syncedAt;

    // 이벤트로 projection이 마지막으로 바뀐 시각
    private LocalDateTime changedAt;

    public boolean isSynced() {
        return syncedAt != null;
    }

    public void recordChange(LocalDateTime changedAt) {
        this.changedAt = changedAt;
    }

    // 늦게 커밋된 이전 조회가 더 최근 적재 시각을 되돌리지 않게 한다.
    public void recordSync(LocalDateTime fetchedAt) {
        if (syncedAt == null || fetchedAt.isAfter(syncedAt)) {
            this.syncedAt = fetchedAt;
        }
    }
}
