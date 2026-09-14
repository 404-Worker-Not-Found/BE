package com.workernotfound.matching.domain.application.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "recruitment_states")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecruitmentState {

	@Id
	@Column(name = "job_post_id", updatable = false)
	private Long jobPostId;

	@Column(name = "completed_job_version")
	private Long completedJobVersion;

	@Column(name = "completion_command_id", length = 36)
	private String completionCommandId;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	public boolean blocks(Long jobVersion) {
		return completedJobVersion != null && completedJobVersion >= jobVersion;
	}

	public boolean complete(Long jobVersion, String commandId, LocalDateTime changedAt) {
		if (blocks(jobVersion)) {
			return false;
		}
		this.completedJobVersion = jobVersion;
		this.completionCommandId = commandId;
		this.completedAt = changedAt;
		return true;
	}
}
