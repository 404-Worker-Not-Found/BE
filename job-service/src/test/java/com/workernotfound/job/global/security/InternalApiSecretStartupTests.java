package com.workernotfound.job.global.security;

import com.workernotfound.job.JobServiceApplication;
import com.workernotfound.job.support.IntegrationTestSupport;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 실제 설정 바인딩과 빈 초기화 경로로 애플리케이션을 띄워 내부 인증값 검증을 확인한다.
 *
 * <p>HTTP 헤더로 보낼 수 없거나 받는 쪽과 비교가 어긋나는 값은 시작 시 거절되어야 하며, 시작 실패 보고와 예외 사슬 어디에도
 * 거절된 값이 나타나면 안 된다. 모든 값은 테스트용 가짜 값이다.
 */
@ExtendWith(OutputCaptureExtension.class)
class InternalApiSecretStartupTests {

    private static final String FAKE = "fake-startup-secret-4b81";

    static Stream<Arguments> invalidSecrets() {
        return Stream.of(
                Arguments.of("line feed", FAKE + "\nX-Injected: 1"),
                Arguments.of("carriage return", FAKE + "\r"),
                Arguments.of("control character", FAKE + "\u0000"),
                Arguments.of("leading space", " " + FAKE),
                Arguments.of("inner space", "fake startup-secret-4b81"),
                Arguments.of("non ascii", FAKE + "é")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSecrets")
    void rejectsHeaderUnsafeSecretWithoutExposingIt(String description, String secret, CapturedOutput output) {
        Throwable failure = catchThrowable(() -> start(secret).close());

        assertThat(failure).isNotNull();
        assertThat(rootCause(failure)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("job.internal.secret");
        String exposedText = stackTraceOf(failure) + output.getAll();
        assertThat(exposedText).doesNotContain("startup-secret-4b81");
    }

    @Test
    void rejectsEmptySecret(CapturedOutput output) {
        Throwable failure = catchThrowable(() -> start("").close());

        assertThat(rootCause(failure)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("job.internal.secret");
    }

    @Test
    void startsWithValidSecretAndKeepsItUnchanged() {
        try (ConfigurableApplicationContext context = start(FAKE)) {
            assertThat(context.getBean(InternalApiProperties.class).secret()).isEqualTo(FAKE);
        }
    }

    // application.yaml의 자리표시자보다 우선하도록 명령행 인자로 넘긴다. 줄바꿈 같은 문자도 값 그대로 바인딩된다.
    private ConfigurableApplicationContext start(String secret) {
        Stream<String> settings = Stream.concat(Stream.of(IntegrationTestSupport.dataSourceProperties()), Stream.of(
                "auth.jwt.secret=startup-test-jwt-secret-at-least-32-bytes",
                "job.matching-seat-reservation.expiry-sweep-enabled=false",
                "job.recruitment-completion.dispatch-enabled=false",
                "job.recruitment-completion.dispatch-after-commit=false",
                "job.recruitment-completion.reconcile.enabled=false",
                "spring.main.banner-mode=off",
                "server.port=0",
                "job.internal.secret=" + secret
        ));
        return new SpringApplicationBuilder(JobServiceApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(settings.map(setting -> "--" + setting).toArray(String[]::new));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String stackTraceOf(Throwable failure) {
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
