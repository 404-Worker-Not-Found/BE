package com.workernotfound.auth.external.client.oauth;

public class OAuth2ClientException extends RuntimeException {

	public OAuth2ClientException(String message) {
		super(message);
	}

	public OAuth2ClientException(Throwable cause) {
		super("OAuth2 provider 호출에 실패했습니다.", cause);
	}
}
