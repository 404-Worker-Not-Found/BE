package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.ApplicationStatusHistory;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationActorType;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.event.ApplicationEvent;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.matching.config.MatchingConfirmationProperties;
import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.MatchingConfirmationSaga;
import com.workernotfound.matching.domain.matching.entity.MatchingStatusHistory;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingActorType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingConfirmationRecoveryAction;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingConfirmationSagaStatus;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.event.MatchingEvent;
import com.workernotfound.matching.domain.matching.exception.MatchingErrorCode;
import com.workernotfound.matching.domain.matching.model.MatchingConfirmationExecution;
import com.workernotfound.matching.domain.matching.model.MatchingConfirmationState;
import com.workernotfound.matching.domain.matching.model.MatchingLockTarget;
import com.workernotfound.matching.domain.matching.repository.MatchingConfirmationSagaRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingStatusHistoryRepository;
import com.workernotfound.matching.domain.outbox.service.OutboxEventCommandService;
import com.workernotfound.matching.external.client.confirmation.dto.SeatReservationResponse;
import com.workernotfound.matching.global.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchingConfirmationCommandService {

	private static final String MATCHING_CONFIRMED_REASON = "MATCHING_CONFIRMED_BY_WORKER";
	private static final String APPLICATION_SELECTED_REASON = "MATCHING_CONFIRMATION_COMPLETED";

	private final ApplicationRepository applicationRepository;
	private final ApplicationStatusHistoryRepository applicationHistoryRepository;
	private final MatchingRepository matchingRepository;
	private final MatchingStatusHistoryRepository matchingHistoryRepository;
	private final MatchingConfirmationSagaRepository sagaRepository;
	private final OutboxEventCommandService outboxEventCommandService;
	private final ApplicationEventPublisher eventPublisher;
	private final MatchingConfirmationProperties properties;

	@Transactional
	public MatchingConfirmationExecution prepare(Long matchingId, Long workerMemberId) {
		MatchingLockTarget target = findTarget(matchingId, workerMemberId);
		Application application = lockApplication(target.applicationId());
		Matching matching = lockMatching(matchingId, workerMemberId);
		if (matching.getStatus() == MatchingStatus.CONFIRMED) {
			return new MatchingConfirmationExecution(null, null, null, true);
		}
		validatePending(application, matching);

		LocalDateTime now = LocalDateTime.now();
		String leaseToken = UUID.randomUUID().toString();
		MatchingConfirmationSaga saga = sagaRepository.findByMatchingIdForUpdate(matchingId).orElse(null);
		if (saga == null) {
			return execution(
				createSaga(matching, leaseToken, now),
				leaseToken,
				MatchingConfirmationExecution.Mode.PROCESS
			);
		}
		return prepareExistingSaga(saga, leaseToken, now);
	}

	@Transactional(readOnly = true)
	public MatchingConfirmationState getState(Long sagaId, String leaseToken) {
		MatchingConfirmationSaga saga = sagaRepository.findById(sagaId)
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.MATCHING_STATE_CONFLICT));
		validateLease(saga, leaseToken);
		return toState(saga);
	}

	@Transactional
	public void recordSeat(Long sagaId, String leaseToken, SeatReservationResponse response) {
		MatchingConfirmationSaga saga = lockSaga(sagaId, leaseToken);
		saga.recordSeat(
			response.reservationId(),
			response.workDate(),
			response.startTime(),
			response.endTime(),
			response.lockedAmount(),
			response.currency()
		);
		renewLease(saga);
	}

	@Transactional
	public void recordPayment(Long sagaId, String leaseToken, String paymentId) {
		MatchingConfirmationSaga saga = lockSaga(sagaId, leaseToken);
		saga.recordPayment(paymentId);
		renewLease(saga);
	}

	@Transactional
	public void recordWork(Long sagaId, String leaseToken, String workId) {
		MatchingConfirmationSaga saga = lockSaga(sagaId, leaseToken);
		saga.recordWork(workId);
		renewLease(saga);
	}

	@Transactional
	public void recordChatRoom(Long sagaId, String leaseToken, String chatRoomId) {
		MatchingConfirmationSaga saga = lockSaga(sagaId, leaseToken);
		saga.recordChatRoom(chatRoomId);
		renewLease(saga);
	}

	@Transactional
	public void recordSeatConsumed(Long sagaId, String leaseToken) {
		MatchingConfirmationSaga saga = lockSaga(sagaId, leaseToken);
		saga.consumeSeat();
		renewLease(saga);
	}

	@Transactional
	public Matching finalizeConfirmation(Long sagaId, String leaseToken, Long workerMemberId) {
		MatchingConfirmationSaga found = sagaRepository.findById(sagaId)
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.MATCHING_STATE_CONFLICT));
		Long matchingId = found.getMatching().getId();
		MatchingLockTarget target = findTarget(matchingId, workerMemberId);
		Application application = lockApplication(target.applicationId());
		Matching matching = lockMatching(matchingId, workerMemberId);
		MatchingConfirmationSaga saga = lockSaga(sagaId, leaseToken);
		if (matching.getStatus() == MatchingStatus.CONFIRMED) {
			return matching;
		}
		validateReadyToFinalize(application, matching, saga);

		LocalDateTime confirmedAt = LocalDateTime.now();
		application.select();
		matching.confirm(confirmedAt);
		applicationRepository.flush();
		matchingRepository.flush();
		applicationHistoryRepository.saveAndFlush(selectedHistory(application, workerMemberId, confirmedAt));
		matchingHistoryRepository.saveAndFlush(confirmedHistory(matching, workerMemberId, confirmedAt));
		publishEvents(application, matching, saga, confirmedAt);
		saga.complete(confirmedAt);
		return matching;
	}

	@Transactional
	public void beginCompensation(Long sagaId, String leaseToken) {
		MatchingConfirmationSaga saga = lockSaga(sagaId, leaseToken);
		saga.beginCompensation(leaseToken, leaseExpiry());
	}

	@Transactional
	public void clearChatRoom(Long sagaId, String leaseToken) {
		lockSaga(sagaId, leaseToken).clearChatRoom();
	}

	@Transactional
	public void clearWork(Long sagaId, String leaseToken) {
		lockSaga(sagaId, leaseToken).clearWork();
	}

	@Transactional
	public void clearPayment(Long sagaId, String leaseToken) {
		lockSaga(sagaId, leaseToken).clearPayment();
	}

	@Transactional
	public void clearSeat(Long sagaId, String leaseToken) {
		lockSaga(sagaId, leaseToken).clearSeat();
	}

	@Transactional
	public void markFailed(
		Long sagaId,
		String leaseToken,
		String step,
		String error,
		MatchingConfirmationRecoveryAction recoveryAction
	) {
		MatchingConfirmationSaga saga = lockSaga(sagaId, leaseToken);
		saga.fail(step, truncate(error), recoveryAction);
	}

	@Transactional
	public void restartAfterCompensation(Long sagaId, String leaseToken) {
		MatchingConfirmationSaga saga = lockSaga(sagaId, leaseToken);
		if (saga.hasResourcesToCompensate()) {
			throw new BusinessException(MatchingErrorCode.MATCHING_CONFIRMATION_FAILED);
		}
		LocalDateTime now = LocalDateTime.now();
		saga.restart(
			newCommandId(),
			newCommandId(),
			newCommandId(),
			newCommandId(),
			newCommandId(),
			newCommandId(),
			newCommandId(),
			newCommandId(),
			newCommandId(),
			leaseToken,
			now.plus(properties.leaseDuration()),
			now
		);
	}

	private MatchingConfirmationExecution prepareExistingSaga(
		MatchingConfirmationSaga saga,
		String leaseToken,
		LocalDateTime now
	) {
		if (saga.getStatus() == MatchingConfirmationSagaStatus.COMPLETED) {
			return new MatchingConfirmationExecution(saga.getId(), null, null, true);
		}
		if (saga.hasActiveLease(now)) {
			throw new BusinessException(MatchingErrorCode.MATCHING_CONFIRMATION_IN_PROGRESS);
		}
		if (saga.getStatus() == MatchingConfirmationSagaStatus.FAILED) {
			return prepareFailedSaga(saga, leaseToken, now);
		}
		if (saga.getStatus() == MatchingConfirmationSagaStatus.COMPENSATING) {
			saga.beginCompensation(leaseToken, now.plus(properties.leaseDuration()));
			return execution(saga, leaseToken, MatchingConfirmationExecution.Mode.COMPENSATE);
		} else {
			saga.resume(leaseToken, now.plus(properties.leaseDuration()));
		}
		return execution(saga, leaseToken, MatchingConfirmationExecution.Mode.PROCESS);
	}

	private MatchingConfirmationExecution prepareFailedSaga(
		MatchingConfirmationSaga saga,
		String leaseToken,
		LocalDateTime now
	) {
		MatchingConfirmationRecoveryAction recoveryAction = saga.getRecoveryAction();
		if (recoveryAction == MatchingConfirmationRecoveryAction.RESUME_COMPENSATION) {
			saga.beginCompensation(leaseToken, now.plus(properties.leaseDuration()));
			return execution(saga, leaseToken, MatchingConfirmationExecution.Mode.COMPENSATE);
		}
		if (recoveryAction == MatchingConfirmationRecoveryAction.START_NEW_ATTEMPT) {
			saga.restart(
				newCommandId(), newCommandId(), newCommandId(),
				newCommandId(), newCommandId(), newCommandId(),
				newCommandId(), newCommandId(), newCommandId(),
				leaseToken, now.plus(properties.leaseDuration()), now
			);
		} else {
			saga.resume(leaseToken, now.plus(properties.leaseDuration()));
		}
		return execution(saga, leaseToken, MatchingConfirmationExecution.Mode.PROCESS);
	}

	private MatchingConfirmationSaga createSaga(Matching matching, String leaseToken, LocalDateTime now) {
		return sagaRepository.saveAndFlush(MatchingConfirmationSaga.builder()
			.matching(matching)
			.seatReservationCommandId(newCommandId())
			.seatConfirmationCommandId(newCommandId())
			.seatCompensationCommandId(newCommandId())
			.paymentLockCommandId(newCommandId())
			.paymentCompensationCommandId(newCommandId())
			.workCreationCommandId(newCommandId())
			.workCompensationCommandId(newCommandId())
			.chatCreationCommandId(newCommandId())
			.chatCompensationCommandId(newCommandId())
			.leaseToken(leaseToken)
			.leaseExpiresAt(now.plus(properties.leaseDuration()))
			.startedAt(now)
			.build());
	}

	private MatchingConfirmationExecution execution(
		MatchingConfirmationSaga saga,
		String leaseToken,
		MatchingConfirmationExecution.Mode mode
	) {
		return new MatchingConfirmationExecution(saga.getId(), leaseToken, mode, false);
	}

	private MatchingLockTarget findTarget(Long matchingId, Long workerMemberId) {
		MatchingLockTarget target = matchingRepository.findLockTargetById(matchingId)
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.MATCHING_NOT_FOUND));
		if (!target.workerMemberId().equals(workerMemberId)) {
			throw new BusinessException(MatchingErrorCode.MATCHING_FORBIDDEN);
		}
		return target;
	}

	private Application lockApplication(Long applicationId) {
		return applicationRepository.findByIdForUpdate(applicationId)
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.MATCHING_NOT_FOUND));
	}

	private Matching lockMatching(Long matchingId, Long workerMemberId) {
		Matching matching = matchingRepository.findByIdForUpdate(matchingId)
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.MATCHING_NOT_FOUND));
		if (!matching.getWorkerMemberId().equals(workerMemberId)) {
			throw new BusinessException(MatchingErrorCode.MATCHING_FORBIDDEN);
		}
		return matching;
	}

	private MatchingConfirmationSaga lockSaga(Long sagaId, String leaseToken) {
		MatchingConfirmationSaga saga = sagaRepository.findByIdForUpdate(sagaId)
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.MATCHING_STATE_CONFLICT));
		validateLease(saga, leaseToken);
		return saga;
	}

	private void validateLease(MatchingConfirmationSaga saga, String leaseToken) {
		if (leaseToken == null
			|| !leaseToken.equals(saga.getLeaseToken())
			|| saga.getLeaseExpiresAt() == null
			|| !saga.getLeaseExpiresAt().isAfter(LocalDateTime.now())) {
			throw new BusinessException(MatchingErrorCode.MATCHING_CONFIRMATION_IN_PROGRESS);
		}
	}

	private void validatePending(Application application, Matching matching) {
		if (application.getStatus() != ApplicationStatus.APPLIED
			|| matching.getStatus() != MatchingStatus.PENDING) {
			throw new BusinessException(MatchingErrorCode.MATCHING_STATE_CONFLICT);
		}
	}

	private void validateReadyToFinalize(
		Application application,
		Matching matching,
		MatchingConfirmationSaga saga
	) {
		validatePending(application, matching);
		if (!saga.isSeatConsumed()
			|| saga.getPaymentId() == null
			|| saga.getWorkId() == null
			|| saga.getChatRoomId() == null) {
			throw new BusinessException(MatchingErrorCode.MATCHING_STATE_CONFLICT);
		}
	}

	private MatchingConfirmationState toState(MatchingConfirmationSaga saga) {
		Matching matching = saga.getMatching();
		return new MatchingConfirmationState(
			saga.getId(), matching.getId(), matching.getApplication().getId(),
			matching.getJobPostId(), matching.getOwnerMemberId(), matching.getWorkerMemberId(),
			saga.getSeatReservationCommandId(), saga.getSeatConfirmationCommandId(),
			saga.getSeatCompensationCommandId(), saga.getPaymentLockCommandId(),
			saga.getPaymentCompensationCommandId(), saga.getWorkCreationCommandId(),
			saga.getWorkCompensationCommandId(), saga.getChatCreationCommandId(),
			saga.getChatCompensationCommandId(),
			saga.getSeatReservationId(), saga.getPaymentId(), saga.getWorkId(), saga.getChatRoomId(),
			saga.getWorkDate(), saga.getStartTime(), saga.getEndTime(), saga.getLockedAmount(),
			saga.getCurrency(), saga.isSeatConsumed()
		);
	}

	private ApplicationStatusHistory selectedHistory(
		Application application,
		Long workerMemberId,
		LocalDateTime changedAt
	) {
		return ApplicationStatusHistory.builder()
			.application(application)
			.fromStatus(ApplicationStatus.APPLIED)
			.toStatus(ApplicationStatus.SELECTED)
			.actorType(ApplicationActorType.WORKER)
			.actorMemberId(workerMemberId)
			.reasonCode(APPLICATION_SELECTED_REASON)
			.revision(application.getRevision())
			.changedAt(changedAt)
			.build();
	}

	private MatchingStatusHistory confirmedHistory(
		Matching matching,
		Long workerMemberId,
		LocalDateTime changedAt
	) {
		return MatchingStatusHistory.builder()
			.matching(matching)
			.fromStatus(MatchingStatus.PENDING)
			.toStatus(MatchingStatus.CONFIRMED)
			.actorType(MatchingActorType.WORKER)
			.actorMemberId(workerMemberId)
			.reasonCode(MATCHING_CONFIRMED_REASON)
			.revision(matching.getRevision())
			.changedAt(changedAt)
			.build();
	}

	private void publishEvents(
		Application application,
		Matching matching,
		MatchingConfirmationSaga saga,
		LocalDateTime confirmedAt
	) {
		String correlationId = matching.getId().toString();
		ApplicationEvent applicationEvent = ApplicationEvent.selected(application, correlationId, confirmedAt);
		outboxEventCommandService.saveApplicationEvent(applicationEvent);
		outboxEventCommandService.saveMatchingEvent(MatchingEvent.confirmed(matching, saga, confirmedAt));
		eventPublisher.publishEvent(applicationEvent);
	}

	private void renewLease(MatchingConfirmationSaga saga) {
		saga.renewLease(leaseExpiry());
	}

	private LocalDateTime leaseExpiry() {
		return LocalDateTime.now().plus(properties.leaseDuration());
	}

	private String newCommandId() {
		return UUID.randomUUID().toString();
	}

	private String truncate(String error) {
		if (error == null || error.length() <= 500) {
			return error;
		}
		return error.substring(0, 500);
	}
}
