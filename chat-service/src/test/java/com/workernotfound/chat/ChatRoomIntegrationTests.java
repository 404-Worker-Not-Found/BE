package com.workernotfound.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.workernotfound.chat.domain.chat.dto.request.ChatRoomRequest;
import com.workernotfound.chat.domain.chat.service.ChatRoomApplicationService;
import com.workernotfound.chat.global.exception.BusinessException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ChatRoomIntegrationTests extends com.workernotfound.chat.support.IntegrationTestSupport {
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.workernotfound.chat.domain.chat.repository.ChatRoomCommandRepository commandRepository;

  static final AtomicLong IDS = new AtomicLong(100);

  @Autowired ChatRoomApplicationService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  ChatRoomRequest request(long matchingId) {
    return new ChatRoomRequest(matchingId, 10L, 20L, 30L, "work-1");
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  long count(String table, long chatRoomId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE chat_room_id = ?", Long.class, chatRoomId);
  }

  @Test
  void failedHistoryWriteRollsBackRoomAndCommand() {
    var request = request(IDS.incrementAndGet());
    String command = key();
    org.mockito.Mockito.doThrow(
            new org.springframework.dao.DataIntegrityViolationException("test history failure"))
        .when(commandRepository)
        .history(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("OPEN"),
            org.mockito.ArgumentMatchers.eq(command));
    try {
      assertThatThrownBy(() -> service.create(command, request))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      org.mockito.Mockito.reset(commandRepository);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_rooms WHERE matching_id = ?",
                Long.class,
                request.matchingId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_commands WHERE command_key = ?", Long.class, command))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_matching_slots WHERE matching_id = ?",
                Long.class,
                request.matchingId()))
        .isZero();
    assertThat(service.create(command, request).chatRoomId()).isNotBlank();
  }

  @Test
  void retryCompensateAndNewAttempt() {
    var request = request(IDS.incrementAndGet());
    String create = key();
    var first = service.create(create, request);
    assertThat(service.create(create, request)).isEqualTo(first);
    String close = key();
    long oldId = Long.parseLong(first.chatRoomId());
    service.close(close, oldId);
    service.close(close, oldId);
    assertThat(service.create(create, request)).isEqualTo(first);
    var next = service.create(key(), request);
    assertThat(next.chatRoomId()).isNotEqualTo(first.chatRoomId());
    service.close(key(), oldId);
    assertThat(
            jdbc.queryForObject(
                "SELECT active_chat_room_id FROM chat_matching_slots WHERE matching_id = ?",
                Long.class,
                request.matchingId()))
        .isEqualTo(Long.parseLong(next.chatRoomId()));
    assertThat(count("chat_status_histories", oldId)).isEqualTo(2);
  }

  @Test
  void mismatchedCommandAndActiveDuplicateAreRejectedWithoutWrites() {
    var r = request(IDS.incrementAndGet());
    String command = key();
    var room = service.create(command, r);
    assertThatThrownBy(() -> service.create(command, request(IDS.incrementAndGet())))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.close(command, Long.parseLong(room.chatRoomId())))
        .isInstanceOf(BusinessException.class);
    String duplicate = key();
    assertThatThrownBy(() -> service.create(duplicate, r)).isInstanceOf(BusinessException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_commands WHERE command_key = ?", Long.class, duplicate))
        .isZero();
  }

  @Test
  void concurrentIdenticalCreationReturnsOneRoom() throws Exception {
    var r = request(IDS.incrementAndGet());
    String command = key();
    var results = concurrent(() -> service.create(command, r).chatRoomId());
    assertThat(new HashSet<>(results)).hasSize(1);
    assertThat(count("chat_status_histories", Long.parseLong(results.get(0)))).isEqualTo(1);
  }

  @Test
  void concurrentDistinctCreationHasOneWinner() throws Exception {
    var r = request(IDS.incrementAndGet());
    var results =
        concurrent(
            () -> {
              try {
                return service.create(key(), r).chatRoomId();
              } catch (BusinessException e) {
                return "conflict";
              }
            });
    assertThat(results.stream().filter(v -> !v.equals("conflict")).count()).isEqualTo(1);
  }

  @Test
  void concurrentClosureRecordsOneTransition() throws Exception {
    long id = Long.parseLong(service.create(key(), request(IDS.incrementAndGet())).chatRoomId());
    concurrent(
        () -> {
          service.close(key(), id);
          return "ok";
        });
    assertThat(count("chat_status_histories", id)).isEqualTo(2);
  }

  List<String> concurrent(Callable<String> operation) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(6);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<String>> futures = new ArrayList<>();
      for (int i = 0; i < 6; i++)
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return operation.call();
                }));
      start.countDown();
      List<String> values = new ArrayList<>();
      for (var future : futures) values.add(future.get(30, TimeUnit.SECONDS));
      return values;
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void authenticationValidationAndSwagger() throws Exception {
    mvc.perform(post("/api/chat-rooms/internal"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
    mvc.perform(post("/ctx/api/chat-rooms/internal").contextPath("/ctx"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/chat-rooms/internal").header("X-Internal-Secret", "wrong"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/chat-rooms/internal")
                .header("X-Internal-Secret", "chat-test-internal-secret")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/chat-rooms/internal")
                .header("X-Internal-Secret", "chat-test-internal-secret")
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/chat-rooms/internal']").exists())
        .andExpect(jsonPath("$.components.schemas.ChatRoomRequest.type").value("object"))
        .andExpect(jsonPath("$.components.schemas.ChatRoomRequest.default").doesNotHaveJsonPath())
        .andExpect(jsonPath("$.components.schemas.ChatRoomRequest.properties.workId.type").value("string"));
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }

  @Test
  void httpContractAcceptsSagaPayloadAndReturnsStringIdentifier() throws Exception {
    String body =
        """
        {"matchingId":%d,"jobPostId":10,"ownerMemberId":20,"workerMemberId":30,
         "workId":"work-1"}
        """
            .formatted(IDS.incrementAndGet());
    String response =
        mvc.perform(
                post("/api/chat-rooms/internal")
                    .header("X-Internal-Secret", "chat-test-internal-secret")
                    .header("Idempotency-Key", key())
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.chatRoomId").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response)
            .path("data")
            .path("chatRoomId")
            .asText();
    mvc.perform(
            post("/api/chat-rooms/internal/" + id + "/close")
                .header("X-Internal-Secret", "chat-test-internal-secret")
                .header("Idempotency-Key", key()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }
  @Test
  void businessErrorsUseDomainCodesAndInvalidInputUsesSafeEnvelope() throws Exception {
    String command = key();
    var request = request(IDS.incrementAndGet());
    var room = service.create(command, request);
    mvc.perform(post("/api/chat-rooms/internal/" + room.chatRoomId() + "/close")
            .header("X-Internal-Secret", "chat-test-internal-secret")
            .header("Idempotency-Key", command))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHAT-409-001"));
    mvc.perform(post("/api/chat-rooms/internal/" + Long.MAX_VALUE + "/close")
            .header("X-Internal-Secret", "chat-test-internal-secret")
            .header("Idempotency-Key", key()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CHAT-404-001"));
    for (String badKey : List.of("bad key", "x".repeat(129))) {
      mvc.perform(post("/api/chat-rooms/internal/" + room.chatRoomId() + "/close")
              .header("X-Internal-Secret", "chat-test-internal-secret")
              .header("Idempotency-Key", badKey))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void sameKeyWithChangedWorkReferenceIsRejected() {
    var request = request(IDS.incrementAndGet());
    String command = key();
    service.create(command, request);
    assertThatThrownBy(() -> service.create(command,
        new ChatRoomRequest(request.matchingId(), 10L, 20L, 30L, "work-2")))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getErrorCode().getCode()).isEqualTo("CHAT-409-001"));
  }

  @Test
  void unexpectedFailureReturns500AndSameCommandCanRecover() throws Exception {
    String command = key();
    var request = request(IDS.incrementAndGet());
    String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request);
    org.mockito.Mockito.doThrow(new IllegalArgumentException("internal detail"))
        .when(commandRepository).history(org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("OPEN"),
            org.mockito.ArgumentMatchers.eq(command));
    try {
      mvc.perform(post("/api/chat-rooms/internal")
              .header("X-Internal-Secret", "chat-test-internal-secret")
              .header("Idempotency-Key", command).contentType("application/json").content(body))
          .andExpect(status().isInternalServerError())
          .andExpect(jsonPath("$.code").value("GLOBAL-500-001"))
          .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("internal detail"))));
    } finally {
      org.mockito.Mockito.reset(commandRepository);
    }
    assertThat(service.create(command, request).chatRoomId()).isNotBlank();
  }

  @Test
  void unsupportedMethodAndMediaTypePreserveProtocolErrors() throws Exception {
    mvc.perform(get("/api/chat-rooms/internal")
            .header("X-Internal-Secret", "chat-test-internal-secret"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
        .andExpect(jsonPath("$.code").value("GLOBAL-405-001"));
    mvc.perform(post("/api/chat-rooms/internal")
            .header("X-Internal-Secret", "chat-test-internal-secret")
            .header("Idempotency-Key", key()).contentType("text/plain").content("{}"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("GLOBAL-415-001"));
  }

}
