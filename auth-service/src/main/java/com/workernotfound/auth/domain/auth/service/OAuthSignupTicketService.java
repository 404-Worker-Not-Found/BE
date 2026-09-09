package com.workernotfound.auth.domain.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OAuthSignupTicketService {

	private static final Duration TICKET_TTL = Duration.ofMinutes(30);
	private static final String TICKET_KEY_FORMAT = "auth:oauth2:signup-ticket:%s";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	public String save(OAuthSignupTicket signupTicket) {
		String ticket = UUID.randomUUID().toString();
		redisTemplate.opsForValue().set(ticketKey(ticket), serialize(signupTicket), TICKET_TTL);
		return ticket;
	}

	public OAuthSignupTicket getAndDelete(String ticket) {
		String value = redisTemplate.opsForValue().getAndDelete(ticketKey(ticket));
		if (value == null) {
			throw new SignupException("OAuth signup ticket이 유효하지 않습니다.");
		}
		return deserialize(value);
	}

	public void restore(String ticket, OAuthSignupTicket signupTicket) {
		Duration remainingTtl = Duration.between(
			LocalDateTime.now(),
			signupTicket.issuedAt().plus(TICKET_TTL)
		);
		if (remainingTtl.isZero() || remainingTtl.isNegative()) {
			return;
		}
		redisTemplate.opsForValue().setIfAbsent(ticketKey(ticket), serialize(signupTicket), remainingTtl);
	}

	private String ticketKey(String ticket) {
		return TICKET_KEY_FORMAT.formatted(ticket);
	}

	private String serialize(OAuthSignupTicket signupTicket) {
		try {
			return objectMapper.writeValueAsString(signupTicket);
		} catch (JsonProcessingException exception) {
			throw new SignupException("OAuth signup ticket 저장에 실패했습니다.");
		}
	}

	private OAuthSignupTicket deserialize(String value) {
		try {
			return objectMapper.readValue(value, OAuthSignupTicket.class);
		} catch (JsonProcessingException exception) {
			throw new SignupException("OAuth signup ticket을 읽을 수 없습니다.");
		}
	}
}
