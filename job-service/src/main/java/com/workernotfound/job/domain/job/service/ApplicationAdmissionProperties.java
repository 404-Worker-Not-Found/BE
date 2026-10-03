package com.workernotfound.job.domain.job.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "job.application-admission")
public record ApplicationAdmissionProperties(
        Duration ttl
) {
}
