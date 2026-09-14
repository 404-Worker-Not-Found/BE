package com.workernotfound.matching.domain.matching.model;

public record MatchingConfirmationExecution(
	Long sagaId,
	String leaseToken,
	Mode mode,
	boolean alreadyConfirmed
) {

	public enum Mode {
		PROCESS,
		COMPENSATE
	}
}
