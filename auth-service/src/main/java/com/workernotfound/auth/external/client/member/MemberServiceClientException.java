package com.workernotfound.auth.external.client.member;

import java.util.Map;
import org.springframework.http.HttpStatusCode;

public class MemberServiceClientException extends RuntimeException {

	private final HttpStatusCode statusCode;
	private final MemberServiceErrorCode errorCode;
	private final String remoteCode;
	private final Map<String, Object> reasons;
	private final FailureType failureType;

	public enum FailureType {
		HTTP_ERROR, INVALID_RESPONSE, RESPONSE_DECODING, CONNECTION_FAILURE, TIMEOUT, TRANSPORT_FAILURE
	}

	public MemberServiceClientException(HttpStatusCode statusCode, MemberServiceErrorCode errorCode) {
		this(statusCode, errorCode, errorCode == null ? null : errorCode.getCode(), null, FailureType.HTTP_ERROR, null);
	}

	public MemberServiceClientException(String message) {
		super(message);
		this.statusCode = null;
		this.errorCode = null;
		this.remoteCode = null;
		this.reasons = null;
		this.failureType = FailureType.INVALID_RESPONSE;
	}

	public MemberServiceClientException(Throwable cause) {
		this(null, null, null, null, FailureType.TRANSPORT_FAILURE, cause);
	}

	MemberServiceClientException(HttpStatusCode statusCode, MemberServiceErrorCode errorCode,
			String remoteCode, Map<String, Object> reasons, FailureType failureType, Throwable cause) {
		super("member-service 호출에 실패했습니다.", cause);
		this.statusCode = statusCode;
		this.errorCode = errorCode;
		this.remoteCode = remoteCode;
		this.reasons = reasons == null || reasons.isEmpty() ? null : Map.copyOf(reasons);
		this.failureType = failureType;
	}

	public String getRemoteCode() {
		return remoteCode;
	}

	public Map<String, Object> getReasons() {
		return reasons;
	}

	public FailureType getFailureType() {
		return failureType;
	}

	public HttpStatusCode getStatusCode() {
		return statusCode;
	}

	public MemberServiceErrorCode getErrorCode() {
		return errorCode;
	}
}
