package com.workernotfound.chat.external.redis;

import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.*;

@Configuration
public class ChatNotificationConfiguration {
  @Bean
  RedisMessageListenerContainer chatNotificationContainer(
      RedisConnectionFactory connections, ChatMessageNotifications notifications) {
    var container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connections);
    container.addMessageListener(notifications, new ChannelTopic(ChatMessageNotifications.CHANNEL));
    return container;
  }
}
