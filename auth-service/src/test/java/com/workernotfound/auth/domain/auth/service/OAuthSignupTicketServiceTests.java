package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;
import com.workernotfound.auth.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthSignupTicketServiceTests extends IntegrationTestSupport {

	@Autowired
	private OAuthSignupTicketService oAuthSignupTicketService;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Test
	void restoresTicketWithOnlyItsRemainingOriginalLifetime() {
		OAuthSignupTicket signupTicket = signupTicket(LocalDateTime.now().minusMinutes(25));
		String ticket = oAuthSignupTicketService.save(signupTicket);
		oAuthSignupTicketService.getAndDelete(ticket);

		oAuthSignupTicketService.restore(ticket, signupTicket);

		Long remainingSeconds = redisTemplate.getExpire(ticketKey(ticket), TimeUnit.SECONDS);
		assertThat(remainingSeconds).isBetween(1L, TimeUnit.MINUTES.toSeconds(5));
		oAuthSignupTicketService.getAndDelete(ticket);
	}

	@Test
	void doesNotRestoreExpiredTicket() {
		OAuthSignupTicket signupTicket = signupTicket(LocalDateTime.now().minusMinutes(31));
		String ticket = oAuthSignupTicketService.save(signupTicket);
		oAuthSignupTicketService.getAndDelete(ticket);

		oAuthSignupTicketService.restore(ticket, signupTicket);

		assertThat(redisTemplate.hasKey(ticketKey(ticket))).isFalse();
	}

	private OAuthSignupTicket signupTicket(LocalDateTime issuedAt) {
		return new OAuthSignupTicket(
			OAuthProvider.KAKAO,
			"kakao-owner",
			"oauth-owner@example.com",
			issuedAt
		);
	}

	private String ticketKey(String ticket) {
		return "auth:oauth2:signup-ticket:%s".formatted(ticket);
	}
}
