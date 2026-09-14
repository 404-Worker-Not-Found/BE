package com.workernotfound.matching.domain.matching.entity;

import com.workernotfound.matching.domain.matching.entity.enums.MatchingConfirmationRecoveryAction;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingConfirmationSagaStatus;
import com.workernotfound.matching.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
	name = "matching_confirmation_sagas",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_matching_confirmation_sagas_matching",
		columnNames = "matching_id"
	),
	indexes = @Index(
		name = "idx_matching_confirmation_sagas_status_lease",
		columnList = "status, lease_expires_at, id"
	)
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchingConfirmationSaga extends BaseEntity {

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
		name = "matching_id",
		nullable = false,
		updatable = false,
		foreignKey = @ForeignKey(name = "fk_matching_confirmation_sagas_matching")
	)
	private Matching matching;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MatchingConfirmationSagaStatus status;

	@Column(nullable = false)
	private Integer attempt;

	@Column(name = "seat_reservation_command_id", nullable = false, length = 36)
	private String seatReservationCommandId;

	@Column(name = "seat_confirmation_command_id", nullable = false, length = 36)
	private String seatConfirmationCommandId;

	@Column(name = "seat_compensation_command_id", nullable = false, length = 36)
	private String seatCompensationCommandId;

	@Column(name = "payment_lock_command_id", nullable = false, length = 36)
	private String paymentLockCommandId;

	@Column(name = "payment_compensation_command_id", nullable = false, length = 36)
	private String paymentCompensationCommandId;

	@Column(name = "work_creation_command_id", nullable = false, length = 36)
	private String workCreationCommandId;

	@Column(name = "work_compensation_command_id", nullable = false, length = 36)
	private String workCompensationCommandId;

	@Column(name = "chat_creation_command_id", nullable = false, length = 36)
	private String chatCreationCommandId;

	@Column(name = "chat_compensation_command_id", nullable = false, length = 36)
	private String chatCompensationCommandId;

	@Column(name = "seat_reservation_id", length = 100)
	private String seatReservationId;

	@Column(name = "payment_id", length = 100)
	private String paymentId;

	@Column(name = "work_id", length = 100)
	private String workId;

	@Column(name = "chat_room_id", length = 100)
	private String chatRoomId;

	@Column(name = "work_date")
	private LocalDate workDate;

	@Column(name = "start_time")
	private LocalTime startTime;

	@Column(name = "end_time")
	private LocalTime endTime;

	@Column(name = "locked_amount", precision = 15, scale = 2)
	private BigDecimal lockedAmount;

	@Column(length = 3)
	private String currency;

	@Column(name = "seat_consumed", nullable = false)
	private boolean seatConsumed;

	@Column(name = "failure_step", length = 30)
	private String failureStep;

	@Enumerated(EnumType.STRING)
	@Column(name = "recovery_action", length = 30)
	private MatchingConfirmationRecoveryAction recoveryAction;

	@Column(name = "last_error", length = 500)
	private String lastError;

	@Column(name = "lease_token", length = 36)
	private String leaseToken;

	@Column(name = "lease_expires_at")
	private LocalDateTime leaseExpiresAt;

	@Column(name = "started_at", nullable = false)
	private LocalDateTime startedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Version
	@Column(nullable = false)
	private Long version;

	@Builder
	private MatchingConfirmationSaga(
		Matching matching,
		String seatReservationCommandId,
		String seatConfirmationCommandId,
		String seatCompensationCommandId,
		String paymentLockCommandId,
		String paymentCompensationCommandId,
		String workCreationCommandId,
		String workCompensationCommandId,
		String chatCreationCommandId,
		String chatCompensationCommandId,
		String leaseToken,
		LocalDateTime leaseExpiresAt,
		LocalDateTime startedAt
	) {
		this.matching = matching;
		this.status = MatchingConfirmationSagaStatus.PROCESSING;
		this.attempt = 1;
		this.seatReservationCommandId = seatReservationCommandId;
		this.seatConfirmationCommandId = seatConfirmationCommandId;
		this.seatCompensationCommandId = seatCompensationCommandId;
		this.paymentLockCommandId = paymentLockCommandId;
		this.paymentCompensationCommandId = paymentCompensationCommandId;
		this.workCreationCommandId = workCreationCommandId;
		this.workCompensationCommandId = workCompensationCommandId;
		this.chatCreationCommandId = chatCreationCommandId;
		this.chatCompensationCommandId = chatCompensationCommandId;
		this.leaseToken = leaseToken;
		this.leaseExpiresAt = leaseExpiresAt;
		this.startedAt = startedAt;
	}

	public boolean hasActiveLease(LocalDateTime now) {
		return leaseToken != null && leaseExpiresAt != null && leaseExpiresAt.isAfter(now);
	}

	public boolean hasResourcesToCompensate() {
		return !seatConsumed && (seatReservationId != null || paymentId != null || workId != null || chatRoomId != null);
	}

	public boolean blocksProposalResponse() {
		return status == MatchingConfirmationSagaStatus.PROCESSING
			|| status == MatchingConfirmationSagaStatus.COMPENSATING
			|| hasResourcesToCompensate()
			|| (status == MatchingConfirmationSagaStatus.FAILED
				&& recoveryAction != MatchingConfirmationRecoveryAction.START_NEW_ATTEMPT);
	}

	public void resume(String newLeaseToken, LocalDateTime newLeaseExpiresAt) {
		this.status = MatchingConfirmationSagaStatus.PROCESSING;
		this.leaseToken = newLeaseToken;
		this.leaseExpiresAt = newLeaseExpiresAt;
		this.failureStep = null;
		this.recoveryAction = null;
		this.lastError = null;
	}

	public void renewLease(LocalDateTime newLeaseExpiresAt) {
		this.leaseExpiresAt = newLeaseExpiresAt;
	}

	public void restart(
		String newSeatCommandId,
		String newSeatConfirmationCommandId,
		String newSeatCompensationCommandId,
		String newPaymentCommandId,
		String newPaymentCompensationCommandId,
		String newWorkCommandId,
		String newWorkCompensationCommandId,
		String newChatCommandId,
		String newChatCompensationCommandId,
		String newLeaseToken,
		LocalDateTime newLeaseExpiresAt,
		LocalDateTime restartedAt
	) {
		this.status = MatchingConfirmationSagaStatus.PROCESSING;
		this.attempt++;
		this.seatReservationCommandId = newSeatCommandId;
		this.seatConfirmationCommandId = newSeatConfirmationCommandId;
		this.seatCompensationCommandId = newSeatCompensationCommandId;
		this.paymentLockCommandId = newPaymentCommandId;
		this.paymentCompensationCommandId = newPaymentCompensationCommandId;
		this.workCreationCommandId = newWorkCommandId;
		this.workCompensationCommandId = newWorkCompensationCommandId;
		this.chatCreationCommandId = newChatCommandId;
		this.chatCompensationCommandId = newChatCompensationCommandId;
		this.leaseToken = newLeaseToken;
		this.leaseExpiresAt = newLeaseExpiresAt;
		this.startedAt = restartedAt;
		this.failureStep = null;
		this.recoveryAction = null;
		this.lastError = null;
	}

	public void recordSeat(
		String reservationId,
		LocalDate workDate,
		LocalTime startTime,
		LocalTime endTime,
		BigDecimal lockedAmount,
		String currency
	) {
		this.seatReservationId = reservationId;
		this.workDate = workDate;
		this.startTime = startTime;
		this.endTime = endTime;
		this.lockedAmount = lockedAmount;
		this.currency = currency;
	}

	public void recordPayment(String paymentId) {
		this.paymentId = paymentId;
	}

	public void recordWork(String workId) {
		this.workId = workId;
	}

	public void recordChatRoom(String chatRoomId) {
		this.chatRoomId = chatRoomId;
	}

	public void consumeSeat() {
		this.seatConsumed = true;
	}

	public void beginCompensation(String newLeaseToken, LocalDateTime newLeaseExpiresAt) {
		this.status = MatchingConfirmationSagaStatus.COMPENSATING;
		this.leaseToken = newLeaseToken;
		this.leaseExpiresAt = newLeaseExpiresAt;
	}

	public void clearChatRoom() {
		this.chatRoomId = null;
	}

	public void clearWork() {
		this.workId = null;
	}

	public void clearPayment() {
		this.paymentId = null;
	}

	public void clearSeat() {
		this.seatReservationId = null;
		this.workDate = null;
		this.startTime = null;
		this.endTime = null;
		this.lockedAmount = null;
		this.currency = null;
	}

	public void fail(
		String failedStep,
		String error,
		MatchingConfirmationRecoveryAction recoveryAction
	) {
		this.status = MatchingConfirmationSagaStatus.FAILED;
		this.failureStep = failedStep;
		this.lastError = error;
		this.recoveryAction = recoveryAction;
		this.leaseToken = null;
		this.leaseExpiresAt = null;
	}

	public void complete(LocalDateTime completedAt) {
		this.status = MatchingConfirmationSagaStatus.COMPLETED;
		this.completedAt = completedAt;
		this.failureStep = null;
		this.recoveryAction = null;
		this.lastError = null;
		this.leaseToken = null;
		this.leaseExpiresAt = null;
	}
}
