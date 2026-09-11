package com.workernotfound.matching.external.client.job;

import org.springframework.http.HttpStatusCode;

public class JobServiceClientException extends RuntimeException {

	private final HttpStatusCode statusCode;
	private final String responseCode;

	public JobServiceClientException(HttpStatusCode statusCode, String responseCode) {
		super("job-service 호출에 실패했습니다. status=%s".formatted(statusCode));
		this.statusCode = statusCode;
		this.responseCode = responseCode;
	}

	public JobServiceClientException(String message) {
		super(message);
		this.statusCode = null;
		this.responseCode = null;
	}

	public JobServiceClientException(Throwable cause) {
		super("job-service 호출 중 오류가 발생했습니다.", cause);
		this.statusCode = null;
		this.responseCode = null;
	}

	public String getResponseCode() {
		return responseCode;
	}
}
