package com.workernotfound.member.global.security;

public class JwtProcessingException extends RuntimeException {
	public JwtProcessingException(Throwable cause) {
		super("JWT 처리 엔진에 오류가 발생했습니다.", cause);
	}
}
