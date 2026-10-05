package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionCommandStatus;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionFailureType;
import com.workernotfound.job.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * matching-service에 모집 완료를 알리는 영속 명령.
 *
 * <p>공고 마감과 같은 트랜잭션에서 저장한다. 명령 ID·공고 ID·완료 버전은 생성 후 바꾸지 않으며 모든 재시도가 같은 값을 보낸다.
 * 전송 상태·실행권·실패 기록은 실행권 토큰을 조건으로 하는 갱신 쿼리로만 바꾼다.
 */
@Getter
@Entity
@Table(
        name = "job_recruitment_completion_commands",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_job_recruitment_completion_commands_command_id",
                        columnNames = "command_id"
                ),
                @UniqueConstraint(
                        name = "uk_job_recruitment_completion_commands_job_version",
                        columnNames = {"job_post_id", "job_version"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecruitmentCompletionCommand extends BaseEntity {

    // 시각 컬럼은 DATETIME(6)이다. 조건 비교가 반올림으로 흔들리지 않도록 저장 전 마이크로초로 절삭한다.
    public static final ChronoUnit TIME_PRECISION = ChronoUnit.MICROS;

    // matching-service의 Idempotency-Key(최대 36자)로 보내는 안정적인 명령 ID
    @Column(nullable = false, updatable = false, length = 36)
    private String commandId;

    @Column(nullable = false, updatable = false)
    private Long jobPostId;

    // 모집 완료 상태 전이가 반영된 공고 버전. 예약 발급 당시 버전이 아니며 재시도에서 다시 읽지 않는다.
    @Column(nullable = false, updatable = false)
    private Long jobVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecruitmentCompletionCommandStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(length = 36)
    private String leaseToken;

    private LocalDateTime leaseExpiresAt;

    private LocalDateTime lastAttemptedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RecruitmentCompletionFailureType lastFailureType;

    private Integer lastFailureHttpStatus;

    // 상대 응답의 오류 코드만 저장한다. 응답 원문은 저장하지 않는다.
    @Column(length = 50)
    private String lastFailureCode;

    private LocalDateTime succeededAt;

    @Builder
    private RecruitmentCompletionCommand(
            String commandId,
            Long jobPostId,
            Long jobVersion,
            LocalDateTime nextAttemptAt
    ) {
        this.commandId = commandId;
        this.jobPostId = jobPostId;
        this.jobVersion = jobVersion;
        this.status = RecruitmentCompletionCommandStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = toStoredTime(nextAttemptAt);
    }

    public static LocalDateTime toStoredTime(LocalDateTime time) {
        return time.truncatedTo(TIME_PRECISION);
    }
}
