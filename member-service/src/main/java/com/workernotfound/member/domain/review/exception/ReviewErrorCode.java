package com.workernotfound.member.domain.review.exception;

import com.workernotfound.member.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReviewErrorCode implements ErrorCode {
	REVIEW_FORBIDDEN("REVIEW-403-001", "이 근무의 참여자만 리뷰할 수 있습니다.", HttpStatus.FORBIDDEN),
	REVIEWABLE_WORK_NOT_FOUND("REVIEW-404-001", "리뷰할 수 있는 완료 근무가 아닙니다.", HttpStatus.NOT_FOUND),
	REVIEW_ALREADY_WRITTEN("REVIEW-409-001", "이 근무에는 이미 다른 내용의 리뷰를 남겼습니다.", HttpStatus.CONFLICT),
	REVIEW_PERIOD_EXPIRED("REVIEW-409-002", "리뷰 작성 기간이 지났습니다.", HttpStatus.CONFLICT);

	private final String code;
	private final String message;
	private final HttpStatus httpStatus;
}
