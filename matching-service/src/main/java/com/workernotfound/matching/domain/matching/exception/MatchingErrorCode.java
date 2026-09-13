package com.workernotfound.matching.domain.matching.exception;

import com.workernotfound.matching.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MatchingErrorCode implements ErrorCode {

	SCORE_BATCH_NOT_FOUND("MATCHING-404-001", "선택 기준으로 사용할 수 있는 점수 묶음이 아닙니다.", HttpStatus.NOT_FOUND),
	MATCHING_NOT_FOUND("MATCHING-404-002", "존재하지 않는 매칭입니다.", HttpStatus.NOT_FOUND),
	MATCHING_FORBIDDEN("MATCHING-403-001", "해당 매칭에 접근할 권한이 없습니다.", HttpStatus.FORBIDDEN),
	APPLICATION_NOT_SELECTABLE("MATCHING-409-001", "현재 지원 상태에서는 매칭 후보로 선택할 수 없습니다.", HttpStatus.CONFLICT),
	MATCHING_ALREADY_EXISTS("MATCHING-409-002", "이미 매칭 시도가 생성된 지원입니다.", HttpStatus.CONFLICT),
	MATCHING_STATE_CONFLICT("MATCHING-409-003", "현재 매칭 상태에서는 요청을 처리할 수 없습니다.", HttpStatus.CONFLICT);

	private final String code;
	private final String message;
	private final HttpStatus httpStatus;
}
