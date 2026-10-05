package com.workernotfound.auth.domain.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.workernotfound.auth.domain.account.repository.ContactChangeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ContactChangeDispatcher {
    private final ContactChangeRepository commands;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ContactChangeTransactionService transactions;
    @Qualifier("memberServiceRestClient") private final RestClient memberServiceRestClient;

    @Scheduled(fixedDelayString = "${auth.account-change.retry-delay-ms:10000}")
    public void retryPending() {
        commands.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc("PENDING", java.time.LocalDateTime.now(), PageRequest.of(0, 50))
                .forEach(command -> dispatch(command.getId()));
    }

    private JsonNode parseResponse(String body) {
        if (body == null) return null;
        try { return objectMapper.readTree(body); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("연락처 변경 응답을 읽을 수 없습니다.");
        }
    }

    public void dispatch(String id) {
        var claimed = transactions.claim(id);
        if (claimed.isEmpty()) return;
        var command = claimed.get();
        try {
            Boolean accepted = memberServiceRestClient.patch().uri("/api/members/internal/{id}/contact", command.memberId())
                .header("Idempotency-Key", command.id())
                .body(Map.of("channel", command.channel(), "target", command.target()))
                .exchange((request, response) -> {
                    JsonNode body = parseResponse(response.bodyTo(String.class));
                    JsonNode data = body == null ? null : body.path("data");
                    if (!response.getStatusCode().is2xxSuccessful() || body == null || !body.path("success").isBoolean()
                            || !body.path("success").booleanValue() || data == null
                            || !data.path("memberId").isIntegralNumber() || data.path("memberId").longValue() != command.memberId()
                            || !command.id().equals(data.path("commandId").textValue())
                            || !command.channel().equals(data.path("channel").textValue())
                            || !command.target().equals(data.path("target").textValue())
                            || !data.path("accepted").isBoolean()) {
                        throw new IllegalStateException("연락처 변경 응답을 확인할 수 없습니다.");
                    }
                    return data.path("accepted").booleanValue();
                });
            transactions.complete(id, !Boolean.TRUE.equals(accepted));
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("연락처 변경 동기화 대기: 재시도 시 동일 요청을 사용합니다.");
        }
    }
}
