package com.workernotfound.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.workernotfound.notification.domain.notification.repository.NotificationRepository;
import com.workernotfound.notification.support.IntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class FundingIncidentApiTests extends IntegrationTestSupport {
  static final String PATH="/api/notifications/internal/funding-incidents";
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @MockitoSpyBean NotificationRepository notifications;
  String owner(long member) {return "{\"jobPostId\":400,\"memberId\":"+member+",\"memberRole\":\"OWNER\",\"occurredAt\":\"2026-10-28T09:00:00\"}";}
  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String key,String body) {
    return post(PATH).header("X-Internal-Secret","test-internal-secret").header("Idempotency-Key",key).contentType("application/json").content(body);
  }
  @Test void requiresInternalSecretAndValidParticipantContract() throws Exception {
    String key=UUID.randomUUID().toString();
    mvc.perform(post(PATH).header("Idempotency-Key",key).contentType("application/json").content(owner(20))).andExpect(status().isUnauthorized());
    mvc.perform(request(key,owner(0))).andExpect(status().isBadRequest());
    mvc.perform(request(key,owner(20).replace("OWNER","WORKER"))).andExpect(status().isBadRequest());
    mvc.perform(request(key,owner(20))).andExpect(status().isOk());
  }
  @Test void duplicateDeliveryRetainsReadStateAndChangedRecipientIsRejected() throws Exception {
    String key=UUID.randomUUID().toString();
    mvc.perform(request(key,owner(20))).andExpect(status().isOk());
    jdbc.update("UPDATE notifications SET read_at=CURRENT_TIMESTAMP(6) WHERE event_id=?","incident:"+key);
    mvc.perform(request(key,owner(20))).andExpect(status().isOk());
    mvc.perform(request(key,owner(21))).andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_id=?",Long.class,"incident:"+key)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT read_at FROM notifications WHERE event_id=?",java.time.LocalDateTime.class,"incident:"+key)).isNotNull();
  }
  @Test void notificationFailureRollsBackReceiptAndRetryCreatesExactlyOne() throws Exception {
    String key=UUID.randomUUID().toString();
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("test rollback")).when(notifications).saveAndFlush(any());
    mvc.perform(request(key,owner(20))).andExpect(status().isInternalServerError());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_events WHERE event_id=?",Long.class,"incident:"+key)).isZero();
    org.mockito.Mockito.reset(notifications);
    mvc.perform(request(key,owner(20))).andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_id=?",Long.class,"incident:"+key)).isEqualTo(1);
  }
  @Test void workerAlertCarriesOnlyTheConfirmedWorkReferences() throws Exception {
    String key=UUID.randomUUID().toString();
    String body="{\"jobPostId\":400,\"memberId\":30,\"memberRole\":\"WORKER\",\"matchingId\":500,\"workId\":600,\"occurredAt\":\"2026-10-28T09:00:00\"}";
    mvc.perform(request(key,body)).andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT notification_type FROM notifications WHERE event_id=?",String.class,"incident:"+key)).isEqualTo("FUNDING_REVIEW_REQUIRED");
    assertThat(jdbc.queryForObject("SELECT work_id FROM notifications WHERE event_id=?",Long.class,"incident:"+key)).isEqualTo(600);
  }
}
