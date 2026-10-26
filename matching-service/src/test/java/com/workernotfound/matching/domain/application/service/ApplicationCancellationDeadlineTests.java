package com.workernotfound.matching.domain.application.service;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.exception.ApplicationErrorCode;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingSelectionType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.global.exception.BusinessException;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

// 지원 취소는 근무 시작 전까지만 허용한다. 시작 시각과 같으면 시작한 것으로 본다.
@Transactional
class ApplicationCancellationDeadlineTests extends IntegrationTestSupport {

	private static final ZoneId ZONE = ZoneId.systemDefault();
	private static final LocalDateTime WORK_STARTS_AT = LocalDateTime.of(2026, 11, 2, 9, 0);
	private static final Long WORKER_MEMBER_ID = 20L;

	@Autowired
	private ApplicationCommandService applicationCommandService;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private ApplicationStatusHistoryRepository historyRepository;

	@Autowired
	private MatchingRepository matchingRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@MockitoBean
	private Clock clock;

	@Test
	void cancelsApplicationAndPendingMatchingJustBeforeWorkStarts() {
		Application application = saveApplication(9301L, WORK_STARTS_AT);
		Matching matching = savePendingMatching(application);
		now(WORK_STARTS_AT.minusNanos(1000));

		Application canceled = applicationCommandService.cancel(application.getId(), WORKER_MEMBER_ID, "cancel");

		assertThat(canceled.getStatus()).isEqualTo(ApplicationStatus.CANCELED);
		assertThat(matchingRepository.findById(matching.getId())).get()
			.extracting(Matching::getStatus).isEqualTo(MatchingStatus.CANCELED);
	}

	@Test
	void rejectsCancellationAtAndAfterWorkStartWithoutChanges() {
		Application application = saveApplication(9302L, WORK_STARTS_AT);
		Matching matching = savePendingMatching(application);
		long histories = historyRepository.findByApplicationIdOrderByRevisionAsc(application.getId()).size();
		long events = outboxEventRepository.count();

		for (LocalDateTime time : new LocalDateTime[] {WORK_STARTS_AT, WORK_STARTS_AT.plusHours(1)}) {
			now(time);
			assertThatThrownBy(() -> applicationCommandService.cancel(application.getId(), WORKER_MEMBER_ID, "cancel"))
				.isInstanceOfSatisfying(BusinessException.class, exception ->
					assertThat(exception.getErrorCode()).isEqualTo(ApplicationErrorCode.CANCELLATION_DEADLINE_PASSED));
		}

		assertThat(applicationRepository.findById(application.getId())).get()
			.extracting(Application::getStatus).isEqualTo(ApplicationStatus.APPLIED);
		assertThat(matchingRepository.findById(matching.getId())).get()
			.extracting(Matching::getStatus).isEqualTo(MatchingStatus.PENDING);
		assertThat(historyRepository.findByApplicationIdOrderByRevisionAsc(application.getId())).hasSize((int) histories);
		assertThat(outboxEventRepository.count()).isEqualTo(events);
	}

	@Test
	void retryOfCanceledApplicationReturnsCanceledEvenAfterWorkStarts() {
		Application application = saveApplication(9303L, WORK_STARTS_AT);
		now(WORK_STARTS_AT.minusHours(1));
		applicationCommandService.cancel(application.getId(), WORKER_MEMBER_ID, "cancel");

		now(WORK_STARTS_AT.plusHours(1));
		Application retried = applicationCommandService.cancel(application.getId(), WORKER_MEMBER_ID, "retry");

		assertThat(retried.getStatus()).isEqualTo(ApplicationStatus.CANCELED);
		assertThat(retried.getRevision()).isEqualTo(2L);
	}

	@Test
	void applicationWithoutWorkStartSnapshotKeepsPreviousBehavior() {
		Application application = saveApplication(9304L, null);
		now(WORK_STARTS_AT.plusDays(30));

		Application canceled = applicationCommandService.cancel(application.getId(), WORKER_MEMBER_ID, "cancel");

		assertThat(canceled.getStatus()).isEqualTo(ApplicationStatus.CANCELED);
	}

	private void now(LocalDateTime time) {
		when(clock.getZone()).thenReturn(ZONE);
		when(clock.instant()).thenReturn(time.atZone(ZONE).toInstant());
	}

	private Application saveApplication(Long admissionId, LocalDateTime workStartsAt) {
		return applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(admissionId)
			.workerMemberId(WORKER_MEMBER_ID)
			.ownerMemberId(100L)
			.jobApplicationAdmissionId(admissionId)
			.appliedAt(WORK_STARTS_AT.minusDays(1))
			.workStartsAt(workStartsAt)
			.build());
	}

	private Matching savePendingMatching(Application application) {
		return matchingRepository.saveAndFlush(Matching.builder()
			.application(application)
			.selectionType(MatchingSelectionType.MANUAL)
			.selectedAt(WORK_STARTS_AT.minusHours(12))
			.build());
	}
}
