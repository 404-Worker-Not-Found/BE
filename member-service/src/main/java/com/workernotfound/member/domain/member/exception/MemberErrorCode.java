package com.workernotfound.member.domain.member.exception;

import com.workernotfound.member.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {
	MEMBER_NOT_FOUND("MEMBER-404-001", "회원을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
	ACTIVE_MEMBER_NOT_FOUND("MEMBER-404-002", "활성 회원을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
	EMAIL_ALREADY_EXISTS("MEMBER-409-001", "이미 사용 중인 이메일입니다.", HttpStatus.CONFLICT),
	PHONE_ALREADY_EXISTS("MEMBER-409-002", "이미 사용 중인 휴대폰 번호입니다.", HttpStatus.CONFLICT),
	ROLE_MISMATCH("MEMBER-400-001", "회원 역할이 요청한 가입 유형과 일치하지 않습니다.", HttpStatus.BAD_REQUEST);

	private final String code;
	private final String message;
	private final HttpStatus httpStatus;
}
