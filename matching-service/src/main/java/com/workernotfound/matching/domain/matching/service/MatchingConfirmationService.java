package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingConfirmationRecoveryAction;
import com.workernotfound.matching.domain.matching.exception.MatchingErrorCode;
import com.workernotfound.matching.domain.matching.model.MatchingConfirmationExecution;
import com.workernotfound.matching.domain.matching.model.MatchingConfirmationState;
import com.workernotfound.matching.external.client.confirmation.ConfirmationStep;
import com.workernotfound.matching.external.client.confirmation.MatchingConfirmationClient;
import com.workernotfound.matching.external.client.confirmation.MatchingConfirmationClientException;
import com.workernotfound.matching.external.client.confirmation.dto.ChatRoomRequest;
import com.workernotfound.matching.external.client.confirmation.dto.ChatRoomResponse;
import com.workernotfound.matching.external.client.confirmation.dto.PaymentLockRequest;
import com.workernotfound.matching.external.client.confirmation.dto.PaymentLockResponse;
import com.workernotfound.matching.external.client.confirmation.dto.ScheduledWorkRequest;
import com.workernotfound.matching.external.client.confirmation.dto.ScheduledWorkResponse;
import com.workernotfound.matching.external.client.confirmation.dto.SeatReservationRequest;
import com.workernotfound.matching.external.client.confirmation.dto.SeatReservationResponse;
import com.workernotfound.matching.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MatchingConfirmationService {

	private final MatchingConfirmationCommandService commandService;
	private final MatchingFindService matchingFindService;
	private final MatchingConfirmationClient client;

	public Matching accept(Long matchingId, Long workerMemberId) {
		MatchingConfirmationExecution execution = commandService.prepare(matchingId, workerMemberId);
		if (execution.alreadyConfirmed()) {
			return matchingFindService.findOwnedMatching(matchingId, workerMemberId);
		}
		if (execution.mode() == MatchingConfirmationExecution.Mode.COMPENSATE) {
			compensateForRecovery(execution);
			commandService.restartAfterCompensation(execution.sagaId(), execution.leaseToken());
		}
		process(execution, workerMemberId);
		return matchingFindService.findOwnedMatching(matchingId, workerMemberId);
	}

	private void process(MatchingConfirmationExecution execution, Long workerMemberId) {
		try {
			reserveSeat(execution);
			lockPayment(execution);
			createWork(execution);
			createChat(execution);
			confirmSeat(execution);
			commandService.finalizeConfirmation(
				execution.sagaId(),
				execution.leaseToken(),
				workerMemberId
			);
		} catch (MatchingConfirmationClientException exception) {
			handleFailure(execution, exception);
			throw mapFailure(exception);
		}
	}

	private void reserveSeat(MatchingConfirmationExecution execution) {
		MatchingConfirmationState state = state(execution);
		if (state.seatReservationId() != null) {
			return;
		}
		SeatReservationResponse response = client.reserveSeat(
			state.jobPostId(),
			new SeatReservationRequest(state.matchingId(), state.applicationId(), state.workerMemberId()),
			state.seatReservationCommandId()
		);
		validateSeat(response, state);
		commandService.recordSeat(execution.sagaId(), execution.leaseToken(), response);
	}

	private void lockPayment(MatchingConfirmationExecution execution) {
		MatchingConfirmationState state = state(execution);
		if (state.paymentId() != null) {
			return;
		}
		PaymentLockResponse response = client.lockPayment(new PaymentLockRequest(
			state.matchingId(), state.jobPostId(), state.ownerMemberId(), state.workerMemberId(),
			state.lockedAmount(), state.currency()
		), state.paymentLockCommandId());
		if (isBlank(response.paymentId())) {
			throw invalidResponse(ConfirmationStep.PAYMENT_LOCK);
		}
		commandService.recordPayment(execution.sagaId(), execution.leaseToken(), response.paymentId());
	}

	private void createWork(MatchingConfirmationExecution execution) {
		MatchingConfirmationState state = state(execution);
		if (state.workId() != null) {
			return;
		}
		ScheduledWorkResponse response = client.createScheduledWork(new ScheduledWorkRequest(
			state.matchingId(), state.jobPostId(), state.ownerMemberId(), state.workerMemberId(),
			state.paymentId(), state.workDate(), state.startTime(), state.endTime()
		), state.workCreationCommandId());
		if (isBlank(response.workId())) {
			throw invalidResponse(ConfirmationStep.WORK_CREATION);
		}
		commandService.recordWork(execution.sagaId(), execution.leaseToken(), response.workId());
	}

	private void createChat(MatchingConfirmationExecution execution) {
		MatchingConfirmationState state = state(execution);
		if (state.chatRoomId() != null) {
			return;
		}
		ChatRoomResponse response = client.createChatRoom(new ChatRoomRequest(
			state.matchingId(), state.jobPostId(), state.ownerMemberId(), state.workerMemberId(), state.workId()
		), state.chatCreationCommandId());
		if (isBlank(response.chatRoomId())) {
			throw invalidResponse(ConfirmationStep.CHAT_CREATION);
		}
		commandService.recordChatRoom(execution.sagaId(), execution.leaseToken(), response.chatRoomId());
	}

	private void confirmSeat(MatchingConfirmationExecution execution) {
		MatchingConfirmationState state = state(execution);
		if (state.seatConsumed()) {
			return;
		}
		client.confirmSeat(state.jobPostId(), state.seatReservationId(), state.seatConfirmationCommandId());
		commandService.recordSeatConsumed(execution.sagaId(), execution.leaseToken());
	}

	private void handleFailure(
		MatchingConfirmationExecution execution,
		MatchingConfirmationClientException original
	) {
		if (original.isOutcomeUnknown()) {
			commandService.markFailed(
				execution.sagaId(), execution.leaseToken(), original.getStep().name(), original.getMessage(),
				MatchingConfirmationRecoveryAction.RESUME_PROCESSING
			);
			return;
		}
		try {
			compensate(execution);
			commandService.markFailed(
				execution.sagaId(), execution.leaseToken(), original.getStep().name(), original.getMessage(),
				MatchingConfirmationRecoveryAction.START_NEW_ATTEMPT
			);
		} catch (MatchingConfirmationClientException compensationFailure) {
			commandService.markFailed(
				execution.sagaId(), execution.leaseToken(),
				compensationFailure.getStep().name(), compensationFailure.getMessage(),
				MatchingConfirmationRecoveryAction.RESUME_COMPENSATION
			);
		}
	}

	private void compensateForRecovery(MatchingConfirmationExecution execution) {
		try {
			compensate(execution);
		} catch (MatchingConfirmationClientException exception) {
			commandService.markFailed(
				execution.sagaId(), execution.leaseToken(), exception.getStep().name(), exception.getMessage(),
				MatchingConfirmationRecoveryAction.RESUME_COMPENSATION
			);
			throw new BusinessException(MatchingErrorCode.MATCHING_CONFIRMATION_FAILED);
		}
	}

	private void compensate(MatchingConfirmationExecution execution) {
		commandService.beginCompensation(execution.sagaId(), execution.leaseToken());
		MatchingConfirmationState state = state(execution);
		if (state.seatConsumed()) {
			throw new BusinessException(MatchingErrorCode.MATCHING_STATE_CONFLICT);
		}
		compensateChat(execution, state);
		compensateWork(execution, state);
		compensatePayment(execution, state);
		compensateSeat(execution, state);
	}

	private void compensateChat(MatchingConfirmationExecution execution, MatchingConfirmationState state) {
		if (state.chatRoomId() == null) {
			return;
		}
		client.closeChatRoom(state.chatRoomId(), state.chatCompensationCommandId());
		commandService.clearChatRoom(execution.sagaId(), execution.leaseToken());
	}

	private void compensateWork(MatchingConfirmationExecution execution, MatchingConfirmationState state) {
		if (state.workId() == null) {
			return;
		}
		client.cancelScheduledWork(state.workId(), state.workCompensationCommandId());
		commandService.clearWork(execution.sagaId(), execution.leaseToken());
	}

	private void compensatePayment(MatchingConfirmationExecution execution, MatchingConfirmationState state) {
		if (state.paymentId() == null) {
			return;
		}
		client.releasePayment(state.paymentId(), state.paymentCompensationCommandId());
		commandService.clearPayment(execution.sagaId(), execution.leaseToken());
	}

	private void compensateSeat(MatchingConfirmationExecution execution, MatchingConfirmationState state) {
		if (state.seatReservationId() == null) {
			return;
		}
		client.releaseSeat(
			state.jobPostId(), state.seatReservationId(), state.seatCompensationCommandId()
		);
		commandService.clearSeat(execution.sagaId(), execution.leaseToken());
	}

	private MatchingConfirmationState state(MatchingConfirmationExecution execution) {
		return commandService.getState(execution.sagaId(), execution.leaseToken());
	}

	private void validateSeat(SeatReservationResponse response, MatchingConfirmationState state) {
		if (isBlank(response.reservationId())
			|| !state.jobPostId().equals(response.jobPostId())
			|| !state.ownerMemberId().equals(response.ownerMemberId())
			|| response.workDate() == null
			|| response.startTime() == null
			|| response.endTime() == null
			|| response.lockedAmount() == null
			|| response.lockedAmount().signum() <= 0
			|| isBlank(response.currency())
			|| response.currency().length() != 3
			|| response.reservedAt() == null
			|| response.expiresAt() == null
			|| !response.expiresAt().isAfter(LocalDateTime.now())) {
			throw invalidResponse(ConfirmationStep.SEAT_RESERVATION);
		}
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private MatchingConfirmationClientException invalidResponse(ConfirmationStep step) {
		return new MatchingConfirmationClientException(step, "매칭 확정 외부 응답이 올바르지 않습니다: " + step);
	}

	private BusinessException mapFailure(MatchingConfirmationClientException exception) {
		if (exception.getStep() == ConfirmationStep.SEAT_RESERVATION && exception.isConflict()) {
			return new BusinessException(MatchingErrorCode.MATCHING_CAPACITY_UNAVAILABLE);
		}
		return new BusinessException(MatchingErrorCode.MATCHING_CONFIRMATION_FAILED);
	}
}
