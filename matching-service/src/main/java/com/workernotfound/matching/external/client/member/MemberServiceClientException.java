package com.workernotfound.matching.external.client.member;

import org.springframework.http.HttpStatusCode;

public class MemberServiceClientException extends RuntimeException {

	private final HttpStatusCode statusCode;
	private final String responseCode;

	public MemberServiceClientException(HttpStatusCode statusCode, String responseCode) {
		super("member-service 호출에 실패했습니다. status=%s".formatted(statusCode));
		this.statusCode = statusCode;
		this.responseCode = responseCode;
	}

	public MemberServiceClientException(String message) {
		super(message);
		this.statusCode = null;
		this.responseCode = null;
	}

	public MemberServiceClientException(Throwable cause) {
		super("member-service 호출 중 오류가 발생했습니다.", cause);
		this.statusCode = null;
		this.responseCode = null;
	}

	public HttpStatusCode getStatusCode() {
		return statusCode;
	}

	public String getResponseCode() {
		return responseCode;
	}
}
