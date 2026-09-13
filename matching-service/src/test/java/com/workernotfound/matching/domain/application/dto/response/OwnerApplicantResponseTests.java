package com.workernotfound.matching.domain.application.dto.response;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.domain.score.entity.enums.ScoreCalculationStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OwnerApplicantResponseTests {

	@Test
	void hidesEveryNumericScoreWhenSnapshotFailed() {
		Application application = mock(Application.class);
		MatchingScoreSnapshot snapshot = mock(MatchingScoreSnapshot.class);
		when(application.getStatus()).thenReturn(ApplicationStatus.APPLIED);
		when(application.getAppliedAt()).thenReturn(LocalDateTime.now());
		when(snapshot.getCalculationStatus()).thenReturn(ScoreCalculationStatus.FAILED);
		when(snapshot.getTotalScore()).thenReturn(BigDecimal.TEN);
		when(snapshot.getAppliedTimeScore()).thenReturn(BigDecimal.TEN);
		when(snapshot.getExpectedArrivalMinutes()).thenReturn(10);

		OwnerApplicantResponse response = OwnerApplicantResponse.from(
			application,
			snapshot,
			"지원자",
			1L,
			List.of("RATING")
		);

		assertThat(response.scoreCalculationStatus()).isEqualTo("FAILED");
		assertThat(response.priorityRank()).isNull();
		assertThat(response.totalScore()).isNull();
		assertThat(response.appliedTimeScore()).isNull();
		assertThat(response.expectedArrivalMinutes()).isNull();
		assertThat(response.missingInputs()).containsExactly("RATING");
	}
}
