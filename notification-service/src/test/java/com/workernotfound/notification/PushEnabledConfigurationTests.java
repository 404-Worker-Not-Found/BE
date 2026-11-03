package com.workernotfound.notification;

import com.workernotfound.notification.domain.push.dto.request.DeviceTokenRequest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.*;
import com.workernotfound.notification.domain.notification.event.NotificationEvent;
import com.workernotfound.notification.domain.notification.service.NotificationEventService;
import com.workernotfound.notification.domain.push.service.*;
import com.workernotfound.notification.global.security.AuthenticatedMember;
import com.workernotfound.notification.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"notification.push.enabled=true", "notification.push.initial-delay-ms=3600000",
    "notification.events.enabled=false", "notification.work-events.enabled=false"})
class PushEnabledConfigurationTests extends IntegrationTestSupport {
  // Replace credential/SDK boundaries: never read a real service-account file or call Google in tests.
  @MockitoBean FirebaseApp notificationFirebaseApp;
  @MockitoBean FirebaseMessaging firebaseMessaging;
  @Autowired ApplicationContext context;
  @Autowired DeviceTokenService devices;
  @Autowired NotificationEventService events;
  @Autowired PushDispatcher dispatcher;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach void clearPushFixtures() {
    jdbc.update("DELETE FROM notification_push_deliveries");
    jdbc.update("DELETE FROM notification_device_tokens");
  }

  @Test void enabledWorkerUsesSeparateSchedulerAndRunsWithoutRedisConsumers() throws Exception {
    assertThat(context.getBean("pushTaskScheduler")).isNotSameAs(context.getBean("taskScheduler"));
    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1);
    long member = 3100000L;
    var token = devices.register(new AuthenticatedMember(member, member, "WORKER"),
        new DeviceTokenRequest("enabled-configuration-token", "ANDROID"));
    events.receive(new NotificationEvent(UUID.randomUUID().toString(), "MatchConfirmed", LocalDateTime.now(),
        member, 1L, 1, member, member + 1, member + 2, member + 3, member, null));
    when(firebaseMessaging.send(any(Message.class))).thenReturn("projects/test/messages/accepted");
    dispatcher.dispatch();
    assertThat(jdbc.queryForObject("SELECT state FROM notification_push_deliveries WHERE device_token_id=?",
        String.class, token.deviceTokenId())).isEqualTo("SENT");
    verify(firebaseMessaging, times(1)).send(any(Message.class));
  }
}
