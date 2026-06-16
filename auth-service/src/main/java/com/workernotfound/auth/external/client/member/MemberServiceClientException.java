package com.workernotfound.auth.external.client.member;

import org.springframework.http.HttpStatusCode;

public class MemberServiceClientException extends RuntimeException {

	private final HttpStatusCode statusCode;
	private final String responseBody;

	public MemberServiceClientException(HttpStatusCode statusCode, String responseBody) {
		super("member-service 호출에 실패했습니다. status=%s, body=%s".formatted(statusCode, responseBody));
		this.statusCode = statusCode;
		this.responseBody = responseBody;
	}

	public MemberServiceClientException(String message) {
		super(message);
		this.statusCode = null;
		this.responseBody = null;
	}

	public MemberServiceClientException(Throwable cause) {
		super("member-service 호출 중 오류가 발생했습니다.", cause);
		this.statusCode = null;
		this.responseBody = null;
	}

	public HttpStatusCode getStatusCode() {
		return statusCode;
	}

	public String getResponseBody() {
		return responseBody;
	}
}
