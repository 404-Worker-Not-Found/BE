package com.workernotfound.matching.external.redis.application;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ApplicationQueueRepository {

	private static final String KEY_FORMAT = "matching:applications:job:%d:active";
	private static final DefaultRedisScript<Long> REPLACE_SCRIPT = new DefaultRedisScript<>("""
		redis.call('DEL', KEYS[1])
		if #ARGV > 0 then
			redis.call('SADD', KEYS[1], unpack(ARGV))
		end
		return #ARGV
		""", Long.class);

	private final StringRedisTemplate redisTemplate;

	public void add(Long jobPostId, Long applicationId) {
		redisTemplate.opsForSet().add(key(jobPostId), applicationId.toString());
	}

	public void remove(Long jobPostId, Long applicationId) {
		redisTemplate.opsForSet().remove(key(jobPostId), applicationId.toString());
	}

	public void replace(Long jobPostId, Collection<Long> applicationIds) {
		redisTemplate.execute(REPLACE_SCRIPT, List.of(key(jobPostId)), toValues(applicationIds));
	}

	public Set<Long> findApplicationIds(Long jobPostId) {
		Set<String> values = redisTemplate.opsForSet().members(key(jobPostId));
		if (values == null) {
			return Set.of();
		}
		return values.stream()
			.map(Long::valueOf)
			.collect(Collectors.toUnmodifiableSet());
	}

	private Object[] toValues(Collection<Long> applicationIds) {
		return applicationIds.stream()
			.map(String::valueOf)
			.toArray();
	}

	private String key(Long jobPostId) {
		return KEY_FORMAT.formatted(jobPostId);
	}
}
