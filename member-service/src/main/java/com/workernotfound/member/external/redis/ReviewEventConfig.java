package com.workernotfound.member.external.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ReviewEventProperties.class)
@ConditionalOnProperty(name = "member.review-events.enabled", havingValue = "true", matchIfMissing = true)
class ReviewEventConfig {
}
