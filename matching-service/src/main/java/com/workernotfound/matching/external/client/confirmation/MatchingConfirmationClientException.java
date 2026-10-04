package com.workernotfound.matching.external.client.confirmation;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

@Getter
public class MatchingConfirmationClientException extends RuntimeException {

	private final ConfirmationStep step;
	private final HttpStatusCode statusCode;
	// 정상 형식의 응답이 명령 거절로 확정된 경우다. 상태 코드가 없어도 결과 불명으로 보지 않는다.
	private final boolean definitiveRejection;

	public MatchingConfirmationClientException(ConfirmationStep step, Throwable cause) {
		super("매칭 확정 외부 요청에 실패했습니다: " + step, cause);
		this.step = step;
		this.statusCode = null;
		this.definitiveRejection = false;
	}

	public MatchingConfirmationClientException(ConfirmationStep step, String message) {
		this(step, message, false);
	}

	private MatchingConfirmationClientException(ConfirmationStep step, String message, boolean definitiveRejection) {
		super(message);
		this.step = step;
		this.statusCode = null;
		this.definitiveRejection = definitiveRejection;
	}

	public static MatchingConfirmationClientException rejected(ConfirmationStep step, String message) {
		return new MatchingConfirmationClientException(step, message, true);
	}

	public MatchingConfirmationClientException(
		ConfirmationStep step,
		HttpStatusCode statusCode,
		Throwable cause
	) {
		super("매칭 확정 외부 요청에 실패했습니다: " + step, cause);
		this.step = step;
		this.statusCode = statusCode;
		this.definitiveRejection = false;
	}

	public boolean isConflict() {
		return statusCode != null && statusCode.value() == 409;
	}

	public boolean isOutcomeUnknown() {
		return !definitiveRejection && (statusCode == null || statusCode.is5xxServerError());
	}
}
