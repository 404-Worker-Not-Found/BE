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
    SEAT_RESERVATION_NOT_FOUND("JOB-404-003", "존재하지 않는 모집 자리 예약입니다.", HttpStatus.NOT_FOUND),
    INVALID_WORK_TIME("JOB-400-001", "종료 시간은 시작 시간보다 이후여야 합니다. 자정을 넘기는 경우 endTimeNextDay를 true로 설정하세요.", HttpStatus.BAD_REQUEST),
    INVALID_APPLICATION_DEADLINE("JOB-400-002", "지원 마감 시간이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_SEARCH_CONDITION("JOB-400-003", "검색 조건이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_WAGE_AMOUNT("JOB-400-004", "급여 계산 금액이 허용 범위를 벗어났습니다.", HttpStatus.BAD_REQUEST),
    JOB_NOT_OPEN("JOB-409-001", "지원을 받지 않는 공고입니다.", HttpStatus.CONFLICT),
    APPLICATION_DEADLINE_PASSED("JOB-409-002", "지원 마감 시간이 지났습니다.", HttpStatus.CONFLICT),
    ADMISSION_EXPIRED("JOB-409-003", "지원 접수 승인이 만료되었습니다. 새 요청으로 다시 시도하세요.", HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED("JOB-409-004", "같은 멱등 키가 다른 요청에 이미 사용되었습니다.", HttpStatus.CONFLICT),
    JOB_NOT_MATCHABLE("JOB-409-005", "매칭을 진행할 수 없는 공고 상태입니다.", HttpStatus.CONFLICT),
    WORK_ALREADY_STARTED("JOB-409-006", "근무 시작 시각이 지나 모집 자리를 예약할 수 없습니다.", HttpStatus.CONFLICT),
    RECRUITMENT_SEAT_UNAVAILABLE("JOB-409-007", "남은 모집 자리가 없습니다.", HttpStatus.CONFLICT),
    SEAT_ALREADY_HELD("JOB-409-008", "같은 매칭 또는 지원이 이미 모집 자리를 점유하고 있습니다.", HttpStatus.CONFLICT),
    SEAT_RESERVATION_EXPIRED("JOB-409-009", "모집 자리 예약이 만료되었습니다.", HttpStatus.CONFLICT),
    SEAT_RESERVATION_STATE_CONFLICT("JOB-409-010", "모집 자리 예약 상태와 맞지 않는 요청입니다.", HttpStatus.CONFLICT),
    FUNDING_ORDER_MISMATCH("JOB-409-011", "공고의 결제 주문과 일치하지 않는 예치 상태 알림입니다.", HttpStatus.CONFLICT),
    FUNDING_REVISION_CONFLICT("JOB-409-012", "같은 주문과 revision의 예치 상태 알림 내용이 다릅니다.", HttpStatus.CONFLICT),
    FUNDING_ORDER_LINK_PENDING("JOB-409-013", "결제 주문 연결이 끝나지 않아 예치 상태를 아직 반영할 수 없습니다. 같은 명령으로 다시 시도하세요.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
