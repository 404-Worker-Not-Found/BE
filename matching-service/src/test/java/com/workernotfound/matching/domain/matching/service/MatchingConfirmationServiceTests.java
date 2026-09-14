package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.application.service.ApplicationCommandService;
import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.MatchingConfirmationSaga;
import com.workernotfound.matching.domain.matching.entity.MatchingStatusHistory;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingActorType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingConfirmationRecoveryAction;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingConfirmationSagaStatus;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingSelectionType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.exception.MatchingErrorCode;
import com.workernotfound.matching.domain.matching.repository.MatchingConfirmationSagaRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingStatusHistoryRepository;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.external.client.confirmation.ConfirmationStep;
import com.workernotfound.matching.external.client.confirmation.MatchingConfirmationClient;
import com.workernotfound.matching.external.client.confirmation.MatchingConfirmationClientException;
import com.workernotfound.matching.external.client.confirmation.dto.ChatRoomResponse;
import com.workernotfound.matching.external.client.confirmation.dto.PaymentLockResponse;
import com.workernotfound.matching.external.client.confirmation.dto.ScheduledWorkResponse;
import com.workernotfound.matching.external.client.confirmation.dto.SeatReservationResponse;
import com.workernotfound.matching.global.exception.BusinessException;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Transactional
class MatchingConfirmationServiceTests extends IntegrationTestSupport {

	@Autowired
	private MatchingConfirmationService confirmationService;

	@Autowired
	private MatchingConfirmationCommandService confirmationCommandService;

	@Autowired
	private ApplicationCommandService applicationCommandService;

	@Autowired
	private MatchingDeclineService matchingDeclineService;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private ApplicationStatusHistoryRepository applicationHistoryRepository;

	@Autowired
	private MatchingRepository matchingRepository;

	@Autowired
	private MatchingStatusHistoryRepository matchingHistoryRepository;

	@Autowired
	private MatchingConfirmationSagaRepository sagaRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@MockitoBean
	private MatchingConfirmationClient client;

	@Test
	void confirmsPendingMatchingAfterAllResourcesAreReady() {
		Matching matching = saveMatching(20L, 30L);
		stubSuccessfulConfirmation();

		Matching confirmed = confirmationService.accept(matching.getId(), 20L);

		assertThat(confirmed.getStatus()).isEqualTo(MatchingStatus.CONFIRMED);
		assertThat(confirmed.getApplication().getStatus()).isEqualTo(ApplicationStatus.SELECTED);
		assertThat(confirmed.getConfirmedAt()).isNotNull();
		MatchingConfirmationSaga saga = sagaRepository.findByMatchingId(matching.getId()).orElseThrow();
		assertThat(saga.getStatus()).isEqualTo(MatchingConfirmationSagaStatus.COMPLETED);
		assertThat(saga.isSeatConsumed()).isTrue();
		assertThat(applicationHistoryRepository.findByApplicationIdOrderByRevisionAsc(
			matching.getApplication().getId()
		)).extracting(history -> history.getToStatus()).containsExactly(ApplicationStatus.SELECTED);
		assertThat(matchingHistoryRepository.findByMatchingIdOrderByRevisionAsc(matching.getId()))
			.extracting(history -> history.getToStatus())
			.containsExactly(MatchingStatus.PENDING, MatchingStatus.CONFIRMED);
		assertThat(outboxEventRepository.findAll())
			.filteredOn(event -> event.getAggregateId().equals(matching.getId()))
			.extracting(event -> event.getEventType())
			.contains("MatchConfirmed");
	}

	@Test
	void repeatedAcceptReturnsConfirmedMatchingWithoutNewExternalCalls() {
		Matching matching = saveMatching(20L, 31L);
		stubSuccessfulConfirmation();
		confirmationService.accept(matching.getId(), 20L);

		Matching repeated = confirmationService.accept(matching.getId(), 20L);

		assertThat(repeated.getStatus()).isEqualTo(MatchingStatus.CONFIRMED);
		assertThat(sagaRepository.findByMatchingId(matching.getId()).orElseThrow().getAttempt()).isEqualTo(1);
	}

	@Test
	void resumesSameAttemptWithoutCompensationWhenWorkCreationOutcomeIsUnknown() {
		Matching matching = saveMatching(20L, 32L);
		when(client.reserveSeat(anyLong(), any(), anyString())).thenReturn(seatResponse(32L));
		when(client.lockPayment(any(), anyString())).thenReturn(new PaymentLockResponse("payment-1"));
		when(client.createScheduledWork(any(), anyString()))
			.thenThrow(new MatchingConfirmationClientException(
				ConfirmationStep.WORK_CREATION,
				new RuntimeException("response lost")
			))
			.thenReturn(new ScheduledWorkResponse("work-1"));
		when(client.createChatRoom(any(), anyString())).thenReturn(new ChatRoomResponse("chat-1"));

		assertThatThrownBy(() -> confirmationService.accept(matching.getId(), 20L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(MatchingErrorCode.MATCHING_CONFIRMATION_FAILED));

		MatchingConfirmationSaga saga = sagaRepository.findByMatchingId(matching.getId()).orElseThrow();
		assertThat(saga.getStatus()).isEqualTo(MatchingConfirmationSagaStatus.FAILED);
		assertThat(saga.getRecoveryAction()).isEqualTo(MatchingConfirmationRecoveryAction.RESUME_PROCESSING);
		assertThat(saga.getSeatReservationId()).isEqualTo("seat-1");
		assertThat(saga.getPaymentId()).isEqualTo("payment-1");

		Matching confirmed = confirmationService.accept(matching.getId(), 20L);

		assertThat(confirmed.getStatus()).isEqualTo(MatchingStatus.CONFIRMED);
		assertThat(sagaRepository.findByMatchingId(matching.getId()).orElseThrow().getAttempt()).isEqualTo(1);
		verify(client, never()).releasePayment(anyString(), anyString());
		verify(client, never()).releaseSeat(anyLong(), anyString(), anyString());
	}

	@Test
	void blocksCancellationAndDeclineWhileAnUnknownOutcomeMustBeRecovered() {
		Matching matching = saveMatching(20L, 40L);
		when(client.reserveSeat(anyLong(), any(), anyString())).thenThrow(
			new MatchingConfirmationClientException(
				ConfirmationStep.SEAT_RESERVATION,
				new RuntimeException("response lost")
			)
		);
		assertThatThrownBy(() -> confirmationService.accept(matching.getId(), 20L))
			.isInstanceOf(BusinessException.class);

		assertThatThrownBy(() -> applicationCommandService.cancel(
			matching.getApplication().getId(),
			20L,
			"cancel-command"
		)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> matchingDeclineService.decline(matching.getId(), 20L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode())
					.isEqualTo(MatchingErrorCode.MATCHING_CONFIRMATION_IN_PROGRESS));
	}

	@Test
	void compensatesPaymentAndSeatWhenWorkCreationIsDefinitelyRejected() {
		Matching matching = saveMatching(20L, 37L);
		when(client.reserveSeat(anyLong(), any(), anyString())).thenReturn(seatResponse(37L));
		when(client.lockPayment(any(), anyString())).thenReturn(new PaymentLockResponse("payment-1"));
		when(client.createScheduledWork(any(), anyString())).thenThrow(
			new MatchingConfirmationClientException(
				ConfirmationStep.WORK_CREATION,
				HttpStatus.BAD_REQUEST,
				new RuntimeException("rejected")
			)
		);

		assertThatThrownBy(() -> confirmationService.accept(matching.getId(), 20L))
			.isInstanceOf(BusinessException.class);

		MatchingConfirmationSaga saga = sagaRepository.findByMatchingId(matching.getId()).orElseThrow();
		assertThat(saga.getRecoveryAction()).isEqualTo(MatchingConfirmationRecoveryAction.START_NEW_ATTEMPT);
		assertThat(saga.getSeatReservationId()).isNull();
		assertThat(saga.getPaymentId()).isNull();
		verify(client).releasePayment("payment-1", saga.getPaymentCompensationCommandId());
		verify(client).releaseSeat(37L, "seat-1", saga.getSeatCompensationCommandId());
	}

	@Test
	void rejectsAcceptByAnotherWorkerBeforeCallingDependencies() {
		Matching matching = saveMatching(20L, 33L);

		assertThatThrownBy(() -> confirmationService.accept(matching.getId(), 21L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(MatchingErrorCode.MATCHING_FORBIDDEN));

		verify(client, never()).reserveSeat(anyLong(), any(), anyString());
	}

	@Test
	void mapsSeatConflictToCapacityUnavailable() {
		Matching matching = saveMatching(20L, 34L);
		when(client.reserveSeat(anyLong(), any(), anyString())).thenThrow(
			new MatchingConfirmationClientException(
				ConfirmationStep.SEAT_RESERVATION,
				HttpStatus.CONFLICT,
				new RuntimeException("full")
			)
		);

		assertThatThrownBy(() -> confirmationService.accept(matching.getId(), 20L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(MatchingErrorCode.MATCHING_CAPACITY_UNAVAILABLE));
	}

	@Test
	void rejectsAnotherCoordinatorWhileConfirmationLeaseIsActive() {
		Matching matching = saveMatching(20L, 35L);
		confirmationCommandService.prepare(matching.getId(), 20L);

		assertThatThrownBy(() -> confirmationCommandService.prepare(matching.getId(), 20L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode())
					.isEqualTo(MatchingErrorCode.MATCHING_CONFIRMATION_IN_PROGRESS));
	}

	@Test
	void startsNewAttemptAfterSuccessfulCompensation() {
		Matching matching = saveMatching(20L, 36L);
		when(client.reserveSeat(anyLong(), any(), anyString())).thenAnswer(invocation ->
			seatResponse(invocation.getArgument(0)));
		when(client.lockPayment(any(), anyString())).thenReturn(new PaymentLockResponse("payment-1"));
		when(client.createScheduledWork(any(), anyString()))
			.thenThrow(new MatchingConfirmationClientException(
				ConfirmationStep.WORK_CREATION,
				HttpStatus.BAD_REQUEST,
				new RuntimeException("first attempt")
			))
			.thenReturn(new ScheduledWorkResponse("work-1"));
		when(client.createChatRoom(any(), anyString())).thenReturn(new ChatRoomResponse("chat-1"));
		assertThatThrownBy(() -> confirmationService.accept(matching.getId(), 20L))
			.isInstanceOf(BusinessException.class);

		Matching confirmed = confirmationService.accept(matching.getId(), 20L);

		assertThat(confirmed.getStatus()).isEqualTo(MatchingStatus.CONFIRMED);
		MatchingConfirmationSaga saga = sagaRepository.findByMatchingId(matching.getId()).orElseThrow();
		assertThat(saga.getAttempt()).isEqualTo(2);
		assertThat(saga.getStatus()).isEqualTo(MatchingConfirmationSagaStatus.COMPLETED);
	}

	@Test
	void retriesSeatConfirmationWithSameCommandWithoutReleasingResourcesAfterResponseLoss() {
		Matching matching = saveMatching(20L, 38L);
		stubSuccessfulConfirmation();
		doThrow(new MatchingConfirmationClientException(
			ConfirmationStep.SEAT_CONFIRMATION,
			new RuntimeException("response lost")
		)).doNothing().when(client).confirmSeat(anyLong(), anyString(), anyString());

		assertThatThrownBy(() -> confirmationService.accept(matching.getId(), 20L))
			.isInstanceOf(BusinessException.class);
		MatchingConfirmationSaga failedSaga = sagaRepository.findByMatchingId(matching.getId()).orElseThrow();
		String confirmationCommandId = failedSaga.getSeatConfirmationCommandId();
		assertThat(failedSaga.getRecoveryAction())
			.isEqualTo(MatchingConfirmationRecoveryAction.RESUME_PROCESSING);

		Matching confirmed = confirmationService.accept(matching.getId(), 20L);

		assertThat(confirmed.getStatus()).isEqualTo(MatchingStatus.CONFIRMED);
		verify(client, times(2)).confirmSeat(38L, "seat-1", confirmationCommandId);
		verify(client, never()).releaseSeat(anyLong(), anyString(), anyString());
		verify(client, never()).releasePayment(anyString(), anyString());
	}

	@Test
	void resumesCompensationWithSameCommandBeforeStartingNewAttempt() {
		Matching matching = saveMatching(20L, 39L);
		when(client.reserveSeat(anyLong(), any(), anyString())).thenAnswer(invocation ->
			seatResponse(invocation.getArgument(0)));
		when(client.lockPayment(any(), anyString())).thenReturn(new PaymentLockResponse("payment-1"));
		when(client.createScheduledWork(any(), anyString()))
			.thenThrow(new MatchingConfirmationClientException(
				ConfirmationStep.WORK_CREATION,
				HttpStatus.BAD_REQUEST,
				new RuntimeException("rejected")
			))
			.thenReturn(new ScheduledWorkResponse("work-1"));
		when(client.createChatRoom(any(), anyString())).thenReturn(new ChatRoomResponse("chat-1"));
		doThrow(new MatchingConfirmationClientException(
			ConfirmationStep.PAYMENT_COMPENSATION,
			new RuntimeException("response lost")
		)).doNothing().when(client).releasePayment(anyString(), anyString());

		assertThatThrownBy(() -> confirmationService.accept(matching.getId(), 20L))
			.isInstanceOf(BusinessException.class);
		MatchingConfirmationSaga failedSaga = sagaRepository.findByMatchingId(matching.getId()).orElseThrow();
		String compensationCommandId = failedSaga.getPaymentCompensationCommandId();
		assertThat(failedSaga.getRecoveryAction())
			.isEqualTo(MatchingConfirmationRecoveryAction.RESUME_COMPENSATION);

		Matching confirmed = confirmationService.accept(matching.getId(), 20L);

		assertThat(confirmed.getStatus()).isEqualTo(MatchingStatus.CONFIRMED);
		assertThat(sagaRepository.findByMatchingId(matching.getId()).orElseThrow().getAttempt()).isEqualTo(2);
		verify(client, times(2)).releasePayment("payment-1", compensationCommandId);
	}

	private void stubSuccessfulConfirmation() {
		when(client.reserveSeat(anyLong(), any(), anyString())).thenAnswer(invocation ->
			seatResponse(invocation.getArgument(0)));
		when(client.lockPayment(any(), anyString())).thenReturn(new PaymentLockResponse("payment-1"));
		when(client.createScheduledWork(any(), anyString())).thenReturn(new ScheduledWorkResponse("work-1"));
		when(client.createChatRoom(any(), anyString())).thenReturn(new ChatRoomResponse("chat-1"));
	}

	private SeatReservationResponse seatResponse(Long jobPostId) {
		return new SeatReservationResponse(
			"seat-1",
			jobPostId,
			1L,
			100L,
			LocalDate.of(2026, 9, 20),
			LocalTime.of(9, 0),
			LocalTime.of(18, 0),
			new BigDecimal("120000.00"),
			"KRW",
			LocalDateTime.now(),
			LocalDateTime.now().plusMinutes(5)
		);
	}

	private Matching saveMatching(Long workerMemberId, Long admissionId) {
		LocalDateTime selectedAt = LocalDateTime.now().minusMinutes(1);
		Application application = applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(admissionId)
			.workerMemberId(workerMemberId)
			.ownerMemberId(100L)
			.jobApplicationAdmissionId(admissionId)
			.appliedAt(selectedAt.minusMinutes(10))
			.build());
		Matching matching = matchingRepository.saveAndFlush(Matching.builder()
			.application(application)
			.selectionType(MatchingSelectionType.MANUAL)
			.selectedAt(selectedAt)
			.build());
		matchingHistoryRepository.saveAndFlush(MatchingStatusHistory.builder()
			.matching(matching)
			.toStatus(MatchingStatus.PENDING)
			.actorType(MatchingActorType.OWNER)
			.actorMemberId(100L)
			.reasonCode("MANUAL_CANDIDATE_SELECTED")
			.revision(1L)
			.changedAt(selectedAt)
			.build());
		return matching;
	}
}
