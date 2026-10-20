package com.workernotfound.payment.external.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(SettlementEventProperties.class)
@ConditionalOnProperty(name = "payment.settlement-events.enabled", havingValue = "true", matchIfMissing = true)
class SettlementEventConfig {}
