package com.workernotfound.member.external.redis;

// 해석할 수 없거나 계약과 다른 근무 이벤트. 기록하지 않고 pending으로 남긴다.
public class InvalidWorkEventException extends RuntimeException {
	public InvalidWorkEventException(String message) {
		super(message);
	}

	public InvalidWorkEventException(String message, Throwable cause) {
		super(message, cause);
	}
}
