package com.workernotfound.auth.domain.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
		String key = ticketKey(ticket);
		String value = redisTemplate.opsForValue().get(key);
		if (value == null) {
			throw new SignupException("OAuth signup ticket이 유효하지 않습니다.");
		}
		redisTemplate.delete(key);
		return deserialize(value);
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
