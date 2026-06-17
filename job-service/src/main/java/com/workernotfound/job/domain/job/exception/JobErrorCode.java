package com.workernotfound.job.domain.job.exception;

import com.workernotfound.job.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum JobErrorCode implements ErrorCode {

    JOB_NOT_FOUND("JOB-404-001", "존재하지 않는 공고입니다.", HttpStatus.NOT_FOUND),
    CATEGORY_NOT_FOUND("JOB-404-002", "존재하지 않는 카테고리입니다.", HttpStatus.NOT_FOUND),
    INVALID_WORK_TIME("JOB-400-001", "종료 시간은 시작 시간보다 이후여야 합니다. 자정을 넘기는 경우 endTimeNextDay를 true로 설정하세요.", HttpStatus.BAD_REQUEST),
    INVALID_APPLICATION_DEADLINE("JOB-400-002", "지원 마감 시간이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_SEARCH_CONDITION("JOB-400-003", "검색 조건이 올바르지 않습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
