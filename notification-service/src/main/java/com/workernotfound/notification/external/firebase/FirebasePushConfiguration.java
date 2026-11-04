package com.workernotfound.notification.external.firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "notification.push.enabled", havingValue = "true")
public class FirebasePushConfiguration {
  @Bean
  public ThreadPoolTaskScheduler pushTaskScheduler() { return scheduler("notification-push-", 1); }

  @Bean
  public ThreadPoolTaskScheduler taskScheduler() { return scheduler("notification-events-", 2); }

  private ThreadPoolTaskScheduler scheduler(String prefix, int size) {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(size);
    scheduler.setThreadNamePrefix(prefix);
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }

  @Bean(destroyMethod = "delete")
  public FirebaseApp notificationFirebaseApp() throws IOException {
    var options = FirebaseOptions.builder().setCredentials(GoogleCredentials.getApplicationDefault())
        .setConnectTimeout(10000).setReadTimeout(10000).setWriteTimeout(10000).build();
    return FirebaseApp.initializeApp(options, "notification-push");
  }

  @Bean
  public FirebaseMessaging firebaseMessaging(FirebaseApp notificationFirebaseApp) {
    return FirebaseMessaging.getInstance(notificationFirebaseApp);
  }
}
