package com.workernotfound.matching.external.redis.application;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ApplicationQueueRepository {

	private static final String KEY_FORMAT = "matching:applications:job:%d:active";

	private final StringRedisTemplate redisTemplate;

	public void add(Long jobPostId, Long applicationId) {
		redisTemplate.opsForSet().add(key(jobPostId), applicationId.toString());
	}

	public void remove(Long jobPostId, Long applicationId) {
		redisTemplate.opsForSet().remove(key(jobPostId), applicationId.toString());
	}

	public void replace(Long jobPostId, Collection<Long> applicationIds) {
		String key = key(jobPostId);
		redisTemplate.delete(key);
		if (!applicationIds.isEmpty()) {
			redisTemplate.opsForSet().add(key, toValues(applicationIds));
		}
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

	private String[] toValues(Collection<Long> applicationIds) {
		return applicationIds.stream()
			.map(String::valueOf)
			.toArray(String[]::new);
	}

	private String key(Long jobPostId) {
		return KEY_FORMAT.formatted(jobPostId);
	}
}
