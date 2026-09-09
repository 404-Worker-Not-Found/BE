package com.workernotfound.auth.external.client.member;

import org.springframework.http.HttpStatusCode;

public class MemberServiceClientException extends RuntimeException {

	private final HttpStatusCode statusCode;
	private final MemberServiceErrorCode errorCode;

	public MemberServiceClientException(HttpStatusCode statusCode, MemberServiceErrorCode errorCode) {
		super("member-service 호출에 실패했습니다. status=%s".formatted(statusCode));
		this.statusCode = statusCode;
		this.errorCode = errorCode;
	}

	public MemberServiceClientException(String message) {
		super(message);
		this.statusCode = null;
		this.errorCode = null;
	}

	public MemberServiceClientException(Throwable cause) {
		super("member-service 호출 중 오류가 발생했습니다.", cause);
		this.statusCode = null;
		this.errorCode = null;
	}

	public HttpStatusCode getStatusCode() {
		return statusCode;
	}

	public MemberServiceErrorCode getErrorCode() {
		return errorCode;
	}
}
