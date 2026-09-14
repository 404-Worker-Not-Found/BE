package com.workernotfound.matching.external.client.confirmation;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

@Getter
public class MatchingConfirmationClientException extends RuntimeException {

	private final ConfirmationStep step;
	private final HttpStatusCode statusCode;

	public MatchingConfirmationClientException(ConfirmationStep step, Throwable cause) {
		super("매칭 확정 외부 요청에 실패했습니다: " + step, cause);
		this.step = step;
		this.statusCode = null;
	}

	public MatchingConfirmationClientException(ConfirmationStep step, String message) {
		super(message);
		this.step = step;
		this.statusCode = null;
	}

	public MatchingConfirmationClientException(
		ConfirmationStep step,
		HttpStatusCode statusCode,
		Throwable cause
	) {
		super("매칭 확정 외부 요청에 실패했습니다: " + step, cause);
		this.step = step;
		this.statusCode = statusCode;
	}

	public boolean isConflict() {
		return statusCode != null && statusCode.value() == 409;
	}

	public boolean isOutcomeUnknown() {
		return statusCode == null || statusCode.is5xxServerError();
	}
}
