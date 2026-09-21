package com.workernotfound.matching.domain.matching;

import static org.assertj.core.api.Assertions.*;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.global.exception.BusinessException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MatchingTransitionErrorTests {
	@Test
	void terminalApplicationsRejectFurtherTransitionsWithoutChangingRevision() {
		Application application =
				Application.builder()
						.jobPostId(1L)
						.workerMemberId(2L)
						.ownerMemberId(3L)
						.jobApplicationAdmissionId(4L)
						.appliedAt(LocalDateTime.now())
						.build();
		application.cancel();
		for (Runnable operation :
				java.util.List.<Runnable>of(
						application::cancel, application::select, application::reject)) {
			assertThatThrownBy(operation::run)
					.isInstanceOfSatisfying(
							BusinessException.class,
							e -> assertThat(e.getErrorCode().getCode()).isEqualTo("APPLICATION-409-002"));
		}
		assertThat(application.getRevision()).isEqualTo(2L);
	}

	@Test
	void terminalMatchingsRejectFurtherTransitionsWithoutChangingRevision() {
		Application application =
				Application.builder().jobPostId(1L).workerMemberId(2L).ownerMemberId(3L).build();
		Matching matching =
				Matching.builder().application(application).selectedAt(LocalDateTime.now()).build();
		matching.decline();
		for (Runnable operation :
				java.util.List.<Runnable>of(
						matching::cancel, matching::decline, () -> matching.confirm(LocalDateTime.now()))) {
			assertThatThrownBy(operation::run)
					.isInstanceOfSatisfying(
							BusinessException.class,
							e -> assertThat(e.getErrorCode().getCode()).isEqualTo("MATCHING-409-003"));
		}
		assertThat(matching.getRevision()).isEqualTo(2L);
	}
}
