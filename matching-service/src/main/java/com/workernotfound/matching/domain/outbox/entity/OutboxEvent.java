package com.workernotfound.matching.domain.outbox.entity;

import com.workernotfound.matching.domain.outbox.entity.enums.OutboxEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(
	name = "outbox_events",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_outbox_events_event_id", columnNames = "event_id"),
		@UniqueConstraint(
			name = "uk_outbox_events_aggregate_revision",
			columnNames = {"aggregate_type", "aggregate_id", "revision"}
		)
	},
	indexes = @Index(
		name = "idx_outbox_events_status_occurred",
		columnList = "status, occurred_at, id"
	)
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", nullable = false, updatable = false, length = 36)
	private String eventId;

	@Column(name = "aggregate_type", nullable = false, updatable = false, length = 50)
	private String aggregateType;

	@Column(name = "aggregate_id", nullable = false, updatable = false)
	private Long aggregateId;

	@Column(name = "event_type", nullable = false, updatable = false, length = 100)
	private String eventType;

	@Column(name = "correlation_id", nullable = false, updatable = false, length = 36)
	private String correlationId;

	@Column(nullable = false, updatable = false)
	private Long revision;

	@Column(name = "schema_version", nullable = false, updatable = false)
	private Integer schemaVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false, columnDefinition = "json")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OutboxEventStatus status;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private LocalDateTime occurredAt;

	@Column(name = "published_at")
	private LocalDateTime publishedAt;

	@Column(name = "retry_count", nullable = false)
	private Integer retryCount;

	@Column(name = "last_error", length = 1000)
	private String lastError;

	@Builder
	private OutboxEvent(
		String eventId,
		String aggregateType,
		Long aggregateId,
		String eventType,
		String correlationId,
		Long revision,
		Integer schemaVersion,
		String payload,
		LocalDateTime occurredAt
	) {
		this.eventId = eventId;
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.correlationId = correlationId;
		this.revision = revision;
		this.schemaVersion = schemaVersion;
		this.payload = payload;
		this.status = OutboxEventStatus.PENDING;
		this.occurredAt = occurredAt;
		this.retryCount = 0;
	}
}
