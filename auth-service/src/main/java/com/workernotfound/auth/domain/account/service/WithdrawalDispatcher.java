package com.workernotfound.auth.domain.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
@Slf4j
public class WithdrawalDispatcher {
	private final WithdrawalTransactionService transactions;
	private final ObjectMapper mapper;
    @Value("${auth.member-service.internal-secret}") private String secret;
    @Value("${auth.member-service.base-url}") private String memberUrl;
    @Value("${auth.withdrawal.job-url}") private String jobUrl;
    @Value("${auth.withdrawal.matching-url}") private String matchingUrl;
    @Value("${auth.withdrawal.work-url}") private String workUrl;
    @Value("${auth.withdrawal.chat-url}") private String chatUrl;
    @Value("${auth.withdrawal.payment-url}") private String paymentUrl;
    @Value("${auth.withdrawal.notification-url}") private String notificationUrl;
    private record Participant(String name, String url, String domain) {}

    @Scheduled(fixedDelayString="${auth.withdrawal.retry-delay-ms:10000}")
    public void retryPending() { transactions.due().forEach(this::dispatch); }

    public void dispatch(String key) {
        var claimed = transactions.claim(key);
        if (claimed.isEmpty()) return;
        var claim = claimed.get();
        String phase = claim.command().state();
        String blocked = claim.command().blockedService();
        try {
            if (phase.equals("CHECKING")) {
                blocked = prepareAll(claim);
                phase = blocked == null ? "FINALIZING" : "RELEASING";
                transactions.advance(claim, phase, blocked);
            }
            String action = phase.equals("RELEASING") ? "release" : "commit";
            String expected = phase.equals("RELEASING") ? "RELEASED" : "COMMITTED";
            for (var participant : participants()) {
                if (!expected.equals(call(participant, claim.command(), action))) throw new IllegalStateException("탈퇴 응답 상태가 올바르지 않습니다.");
            }
            transactions.finish(claim, phase.equals("RELEASING"));
        } catch (RestClientException | IllegalStateException | DataAccessException exception) {
            log.warn("회원 탈퇴 처리를 다음 주기에 재시도합니다: phase={}", phase);
        } finally { transactions.releaseLease(claim); }
    }

    private String prepareAll(WithdrawalTransactionService.Claim claim) {
        String blocked = null;
        for (var participant : participants()) {
            String state = call(participant, claim.command(), "prepare");
            if (state.equals("REJECTED")) blocked = participant.name();
            else if (!state.equals("PREPARED")) throw new IllegalStateException("탈퇴 준비 결과가 올바르지 않습니다.");
        }
        return blocked;
    }

    private String call(Participant participant, WithdrawalTransactionService.State command, String action) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(participant.url()).requestFactory(factory).build().post()
            .uri("/api/{domain}/internal/account-withdrawals/{member}/{action}", participant.domain(), command.memberId(), action)
            .header("X-Internal-Secret", secret).body(Map.of("commandId", command.commandId()))
            .exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("탈퇴 의존 서비스가 정상 응답하지 않았습니다.");
                var body = mapper.readTree(response.bodyTo(String.class));
                var data = body == null ? null : body.path("data");
                if (body == null || !body.path("success").isBoolean() || !body.path("success").booleanValue() || data == null
                        || !data.path("memberId").isIntegralNumber() || data.path("memberId").longValue() != command.memberId()
                        || !command.commandId().equals(data.path("commandId").textValue()) || !data.path("state").isTextual()) {
                    throw new IllegalStateException("탈퇴 응답 계약이 올바르지 않습니다.");
                }
                return data.path("state").textValue();
            });
    }
    private List<Participant> participants() {
        return List.of(new Participant("member",memberUrl,"members"),new Participant("job",jobUrl,"jobs"),
                new Participant("matching",matchingUrl,"applications"),new Participant("work",workUrl,"works"),
                new Participant("payment",paymentUrl,"payments"),new Participant("chat",chatUrl,"chat-rooms"),
                new Participant("notification",notificationUrl,"notifications"));
    }
}
