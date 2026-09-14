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
	private static final String METADATA_KEY_FORMAT = "matching:applications:job:%d:active:metadata";
	private static final DefaultRedisScript<Long> ADD_SCRIPT = new DefaultRedisScript<>("""
		local currentFenceToken = redis.call('HGET', KEYS[2], 'fenceToken')
		if currentFenceToken and tonumber(currentFenceToken) > tonumber(ARGV[1]) then
			return -1
		end
		redis.call('SADD', KEYS[1], ARGV[2])
		redis.call('HSET', KEYS[2], 'fenceToken', ARGV[1])
		return 1
		""", Long.class);
	private static final DefaultRedisScript<Long> REMOVE_SCRIPT = new DefaultRedisScript<>("""
		local currentFenceToken = redis.call('HGET', KEYS[2], 'fenceToken')
		if currentFenceToken and tonumber(currentFenceToken) > tonumber(ARGV[1]) then
			return -1
		end
		redis.call('SREM', KEYS[1], ARGV[2])
		redis.call('HSET', KEYS[2], 'fenceToken', ARGV[1])
		return 1
		""", Long.class);
	private static final DefaultRedisScript<Long> REPLACE_SCRIPT = new DefaultRedisScript<>("""
		local currentFenceToken = redis.call('HGET', KEYS[2], 'fenceToken')
		if currentFenceToken and tonumber(currentFenceToken) > tonumber(ARGV[1]) then
			return -1
		end
		redis.call('DEL', KEYS[1])
		if #ARGV > 1 then
			redis.call('SADD', KEYS[1], unpack(ARGV, 2))
		end
		redis.call('HSET', KEYS[2], 'fenceToken', ARGV[1])
		return #ARGV - 1
		""", Long.class);

	private final StringRedisTemplate redisTemplate;

	public void add(Long jobPostId, Long applicationId, Long fenceToken) {
		redisTemplate.execute(
			ADD_SCRIPT,
			List.of(key(jobPostId), metadataKey(jobPostId)),
			fenceToken.toString(),
			applicationId.toString()
		);
	}

	public void remove(Long jobPostId, Long applicationId, Long fenceToken) {
		redisTemplate.execute(
			REMOVE_SCRIPT,
			List.of(key(jobPostId), metadataKey(jobPostId)),
			fenceToken.toString(),
			applicationId.toString()
		);
	}

	public void replace(Long jobPostId, Collection<Long> applicationIds, Long fenceToken) {
		redisTemplate.execute(
			REPLACE_SCRIPT,
			List.of(key(jobPostId), metadataKey(jobPostId)),
			toValues(applicationIds, fenceToken)
		);
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

	private Object[] toValues(Collection<Long> applicationIds, Long fenceToken) {
		String[] values = new String[applicationIds.size() + 1];
		values[0] = fenceToken.toString();
		int index = 1;
		for (Long applicationId : applicationIds) {
			values[index++] = applicationId.toString();
		}
		return values;
	}

	private String key(Long jobPostId) {
		return KEY_FORMAT.formatted(jobPostId);
	}

	private String metadataKey(Long jobPostId) {
		return METADATA_KEY_FORMAT.formatted(jobPostId);
	}
}
