package com.workernotfound.member.domain.review.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReviewProperties.class)
class ReviewPolicyConfig {
}
