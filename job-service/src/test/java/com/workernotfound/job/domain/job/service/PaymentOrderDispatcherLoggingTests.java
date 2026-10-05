package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderFailureType;
import com.workernotfound.job.domain.job.exception.PaymentOrderCreationException;
import com.workernotfound.job.domain.job.port.PaymentOrderCreator;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.MutableClock;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * 전송 경계 로그 검증: 요청 헤더·비밀값·응답 원문·예외 메시지가 로그에 나가지 않고, 내부 오류가 같은 배치의 다음 명령을 막지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaymentOrderDispatcherLoggingTests extends IntegrationTestSupport {

    private static final String SECRET = "test-internal-secret";

    @MockitoSpyBean
    private PaymentOrderCreator creator;

    @Autowired
    private PaymentOrderDispatcher dispatcher;

    @Autowired
    private JobCommandService jobCommandService;

    @Autowired
    private JobPaymentOrderCommandRepository commandRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        reset(creator);
        clock.fixAtNow();
        jdbcTemplate.update("update job_payment_order_commands set status = 'SUPERSEDED' where status = 'PENDING'");
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    @Test
    void logsOnlyCommandIdAndExceptionTypesForInternalError(CapturedOutput output) {
        JobPaymentOrderCommand failing = createJob(950_001L);
        JobPaymentOrderCommand next = createJob(950_002L);
        doThrow(new IllegalStateException("X-Internal-Secret: " + SECRET + " key=" + failing.getIdempotencyKey(),
                new RuntimeException("{\"raw\":\"response body\"}")))
                .doThrow(new PaymentOrderCreationException(PaymentOrderFailureType.CONTRACT, 200, (String) null))
                .when(creator).createOrder(any());

        // 내부 오류가 난 명령은 세지 않고, 같은 배치의 다음 명령은 계속 처리한다.
        assertThat(dispatcher.dispatchDue()).isOne();

        assertThat(reload(failing).getStatus()).isEqualTo(PaymentOrderCommandStatus.PENDING);
        assertThat(reload(failing).getLeaseToken()).isNotNull();
        assertThat(reload(next).getLastFailureType()).isEqualTo(PaymentOrderFailureType.CONTRACT);
        assertThat(output.getAll())
                .contains("결제 주문 생성 명령 처리 실패: id=" + failing.getId())
                .contains("java.lang.IllegalStateException <- java.lang.RuntimeException")
                .contains("[운영 확인 필요] 결제 주문 생성 실패: commandId=" + next.getId())
                .doesNotContain(SECRET)
                .doesNotContain(failing.getIdempotencyKey())
                .doesNotContain(next.getIdempotencyKey())
                .doesNotContain("response body");
    }

    private JobPaymentOrderCommand createJob(long ownerId) {
        LocalDate workDate = LocalDate.now().plusDays(2);
        Long jobPostId = jobCommandService.create(ownerId, new CreateJobRequest(
                1L, 1L, "테스트 상점", "서울시 마포구", "로그 검증 공고", "설명",
                workDate, LocalTime.of(9, 0), LocalTime.of(18, 0), false, 10_320, null, 1,
                new BigDecimal("37.5665000"), new BigDecimal("126.9780000"), "MEDIUM",
                LocalDateTime.of(workDate.minusDays(1), LocalTime.NOON)));
        return commandRepository.findByJobPostIdOrderByIssueSequenceAsc(jobPostId).get(0);
    }

    private JobPaymentOrderCommand reload(JobPaymentOrderCommand command) {
        return commandRepository.findById(command.getId()).orElseThrow();
    }
}
