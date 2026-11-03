package com.workernotfound.member.global.config;

import com.workernotfound.member.domain.outbox.MemberOutboxProperties;
import com.workernotfound.member.domain.outbox.MemberOutboxRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MemberOutboxProperties.class)
public class MemberOutboxConfiguration {

	@Configuration
	@RequiredArgsConstructor
	@ConditionalOnProperty(name = "member.outbox.enabled", havingValue = "true", matchIfMissing = true)
	static class Scheduler {
		private final MemberOutboxRelay relay;

		@Scheduled(fixedDelayString = "${member.outbox.poll-delay:1s}", initialDelayString = "${member.outbox.initial-delay:1s}")
		public void publish() {
			relay.relay();
		}
	}
}
