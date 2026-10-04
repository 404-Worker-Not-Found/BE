package com.workernotfound.job.support;

import com.workernotfound.job.domain.job.port.RecruitmentCompletionNotifier;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 호출마다 미리 정한 예외를 던지거나 성공하는 모집 완료 알림 대역.
 *
 * <p>실행 경계의 로그 처리를 검증하기 위해 실제 HTTP 클라이언트 대신 사용한다. 실제 HTTP 동작은 클라이언트 테스트가 검증한다.
 */
public class ScriptedRecruitmentCompletionNotifier implements RecruitmentCompletionNotifier {

    // 실제 비밀값이 아닌 테스트용 값이다. 로그에 이 문자열이 나타나면 노출로 본다.
    public static final String FAKE_SECRET = "fake-internal-secret-7f3a9c";

    private final Queue<Supplier<RuntimeException>> failures = new ConcurrentLinkedQueue<>();
    private final List<String> calledCommandIds = new CopyOnWriteArrayList<>();

    public void failNextWith(Supplier<RuntimeException> failure) {
        failures.add(failure);
    }

    public List<String> calledCommandIds() {
        return List.copyOf(calledCommandIds);
    }

    public void reset() {
        failures.clear();
        calledCommandIds.clear();
    }

    @Override
    public void notifyRecruitmentCompleted(Long jobPostId, Long jobVersion, String commandId) {
        calledCommandIds.add(commandId);
        Supplier<RuntimeException> failure = failures.poll();
        if (failure != null) {
            throw failure.get();
        }
    }

    @Override
    public Duration maxCallDuration() {
        return Duration.ofSeconds(1);
    }

    // JDK HTTP 클라이언트가 잘못된 헤더 값을 거절할 때처럼 메시지·중첩 cause·suppressed 예외에 비밀값이 담긴 예외
    public static RuntimeException exceptionCarryingSecret() {
        IllegalArgumentException headerFailure = new IllegalArgumentException(
                "invalid header value: \"" + FAKE_SECRET + "\nX-Injected: 1\"");
        IllegalStateException nested = new IllegalStateException("nested " + FAKE_SECRET, headerFailure);
        IllegalArgumentException topLevel = new IllegalArgumentException("invalid header value: \"" + FAKE_SECRET + "\"", nested);
        topLevel.addSuppressed(new IllegalStateException("suppressed " + FAKE_SECRET));
        return topLevel;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        public ScriptedRecruitmentCompletionNotifier scriptedRecruitmentCompletionNotifier() {
            return new ScriptedRecruitmentCompletionNotifier();
        }
    }
}
