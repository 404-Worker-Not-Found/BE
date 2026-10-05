package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.port.RecruitmentCompletionNotifier;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실행권은 실제로 강제되는 전체 호출 제한시간에 취소·기록 여유(1초)를 더한 값 이상이어야 한다.
class RecruitmentCompletionDispatcherLeaseTests {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(8);

    @Test
    void rejectsLeaseShorterThanOrEqualToCallTimeout() {
        assertRejected(CALL_TIMEOUT.minusMillis(1));
        assertRejected(CALL_TIMEOUT);
    }

    @Test
    void rejectsLeaseWithoutCancellationAndRecordingMargin() {
        assertRejected(CALL_TIMEOUT.plus(RecruitmentCompletionDispatcher.LEASE_MARGIN).minusMillis(1));
    }

    @Test
    void acceptsLeaseCoveringCallTimeoutAndMargin() {
        assertThatCode(() -> dispatcher(CALL_TIMEOUT.plus(RecruitmentCompletionDispatcher.LEASE_MARGIN)))
                .doesNotThrowAnyException();
    }

    private void assertRejected(Duration lease) {
        assertThatThrownBy(() -> dispatcher(lease))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-duration")
                .hasMessageContaining("call-timeout");
    }

    private RecruitmentCompletionDispatcher dispatcher(Duration lease) {
        RecruitmentCompletionDispatchProperties properties = new RecruitmentCompletionDispatchProperties(
                false, Duration.ofSeconds(5), 50, lease, Duration.ofSeconds(2), Duration.ofMinutes(5));
        return new RecruitmentCompletionDispatcher(null, null, new FixedCallDurationNotifier(), properties, Clock.systemUTC());
    }

    private static final class FixedCallDurationNotifier implements RecruitmentCompletionNotifier {

        @Override
        public void notifyRecruitmentCompleted(Long jobPostId, Long jobVersion, String commandId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Duration maxCallDuration() {
            return CALL_TIMEOUT;
        }
    }
}
