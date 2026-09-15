package com.workernotfound.member.domain.worker.exception;

import com.workernotfound.member.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum WorkerErrorCode implements ErrorCode {
	WORKER_PROFILE_NOT_FOUND("WORKER-404-001", "근로자 프로필을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
	DUPLICATE_BUSINESS_TYPE("WORKER-400-001", "선호 업종이 중복되었습니다.", HttpStatus.BAD_REQUEST),
	INVALID_AVAILABLE_TIME("WORKER-400-002", "근무 시작 시간은 종료 시간보다 빨라야 합니다.", HttpStatus.BAD_REQUEST);

	private final String code;
	private final String message;
	private final HttpStatus httpStatus;
}
