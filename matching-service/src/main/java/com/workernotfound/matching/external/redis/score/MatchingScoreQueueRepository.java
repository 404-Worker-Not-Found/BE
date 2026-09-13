package com.workernotfound.matching.external.redis.score;

import com.workernotfound.matching.domain.score.model.RankedApplicationScore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MatchingScoreQueueRepository {

	private static final String QUEUE_KEY_FORMAT = "matching:applications:job:%d:scores:%d";
	private static final String QUEUE_KEY_PREFIX_FORMAT = "matching:applications:job:%d:scores:";
	private static final String METADATA_KEY_FORMAT = "matching:applications:job:%d:scores:metadata";
	private static final String BATCH_ID_FIELD = "scoreBatchId";
	private static final String POLICY_VERSION_FIELD = "policyVersion";
	private static final DefaultRedisScript<Long> REPLACE_SCRIPT = new DefaultRedisScript<>("""
		local previousBatchId = redis.call('HGET', KEYS[1], 'scoreBatchId')
		if previousBatchId then
			redis.call('DEL', KEYS[2] .. previousBatchId)
		end
		redis.call('DEL', KEYS[3])
		for index = 3, #ARGV, 2 do
			redis.call('ZADD', KEYS[3], ARGV[index + 1], ARGV[index])
		end
		redis.call('HSET', KEYS[1], 'scoreBatchId', ARGV[1], 'policyVersion', ARGV[2])
		return (#ARGV - 2) / 2
		""", Long.class);
	private static final DefaultRedisScript<Long> CLEAR_SCRIPT = new DefaultRedisScript<>("""
		local previousBatchId = redis.call('HGET', KEYS[1], 'scoreBatchId')
		if previousBatchId then
			redis.call('DEL', KEYS[2] .. previousBatchId)
		end
		return redis.call('DEL', KEYS[1])
		""", Long.class);

	private final StringRedisTemplate redisTemplate;

	public void replace(
		Long jobPostId,
		Long scoreBatchId,
		String policyVersion,
		List<RankedApplicationScore> scores
	) {
		redisTemplate.execute(
			REPLACE_SCRIPT,
			List.of(metadataKey(jobPostId), queueKeyPrefix(jobPostId), queueKey(jobPostId, scoreBatchId)),
			arguments(scoreBatchId, policyVersion, scores)
		);
	}

	public void clear(Long jobPostId) {
		redisTemplate.execute(
			CLEAR_SCRIPT,
			List.of(metadataKey(jobPostId), queueKeyPrefix(jobPostId))
		);
	}

	public Optional<MatchingScoreQueueMetadata> findMetadata(Long jobPostId) {
		Map<Object, Object> metadata = redisTemplate.opsForHash().entries(metadataKey(jobPostId));
		if (!metadata.containsKey(BATCH_ID_FIELD) || !metadata.containsKey(POLICY_VERSION_FIELD)) {
			return Optional.empty();
		}
		return Optional.of(new MatchingScoreQueueMetadata(
			Long.valueOf(metadata.get(BATCH_ID_FIELD).toString()),
			metadata.get(POLICY_VERSION_FIELD).toString()
		));
	}

	public Set<RankedApplicationScore> findScores(Long jobPostId, Long scoreBatchId) {
		Set<TypedTuple<String>> values = redisTemplate.opsForZSet()
			.reverseRangeWithScores(queueKey(jobPostId, scoreBatchId), 0, -1);
		if (values == null) {
			return Set.of();
		}
		return values.stream()
			.map(this::toScore)
			.collect(Collectors.toUnmodifiableSet());
	}

	private Object[] arguments(
		Long scoreBatchId,
		String policyVersion,
		List<RankedApplicationScore> scores
	) {
		List<String> arguments = new ArrayList<>();
		arguments.add(scoreBatchId.toString());
		arguments.add(policyVersion);
		scores.forEach(score -> {
			arguments.add(score.applicationId().toString());
			arguments.add(score.totalScore().toPlainString());
		});
		return arguments.toArray();
	}

	private RankedApplicationScore toScore(TypedTuple<String> value) {
		return new RankedApplicationScore(
			Long.valueOf(value.getValue()),
			BigDecimal.valueOf(value.getScore())
		);
	}

	private String metadataKey(Long jobPostId) {
		return METADATA_KEY_FORMAT.formatted(jobPostId);
	}

	private String queueKey(Long jobPostId, Long scoreBatchId) {
		return QUEUE_KEY_FORMAT.formatted(jobPostId, scoreBatchId);
	}

	private String queueKeyPrefix(Long jobPostId) {
		return QUEUE_KEY_PREFIX_FORMAT.formatted(jobPostId);
	}
}
