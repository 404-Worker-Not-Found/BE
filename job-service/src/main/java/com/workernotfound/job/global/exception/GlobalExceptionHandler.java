package com.workernotfound.job.global.exception;

import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@lombok.extern.slf4j.Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 롤백 이후 비즈니스 오류로 변환할 제약 이름과 오류 코드. 이름이 정확히 일치하는 제약만 변환한다.
    private static final Map<String, JobErrorCode> CONSTRAINT_ERRORS = Map.of(
            "uk_job_application_admissions_idempotency_key", JobErrorCode.IDEMPOTENCY_KEY_REUSED,
            "uk_job_matching_seat_reservations_idempotency_key", JobErrorCode.IDEMPOTENCY_KEY_REUSED,
            "uk_job_matching_seat_reservations_confirm_key", JobErrorCode.IDEMPOTENCY_KEY_REUSED,
            "uk_job_matching_seat_reservations_release_key", JobErrorCode.IDEMPOTENCY_KEY_REUSED,
            "uk_job_funding_status_receipts_idempotency_key", JobErrorCode.IDEMPOTENCY_KEY_REUSED,
            "uk_job_funding_status_receipts_order_revision", JobErrorCode.FUNDING_REVISION_CONFLICT,
            "uk_job_payment_change_requests_idempotency_key", JobErrorCode.IDEMPOTENCY_KEY_REUSED,
            "uk_job_close_requests_idempotency_key", JobErrorCode.IDEMPOTENCY_KEY_REUSED);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception, HttpServletRequest request) {
        return error(exception.getErrorCode(), exception.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, Object> reasons =
                exception.getBindingResult().getFieldErrors().stream()
                        .collect(
                                Collectors.toMap(
                                        fieldError -> fieldError.getField(),
                                        GlobalExceptionHandler::safeFieldMessage,
                                        (first, second) -> first));
        return error(
                GlobalErrorCode.VALIDATION_ERROR,
                GlobalErrorCode.VALIDATION_ERROR.getMessage(),
                request.getRequestURI(),
                reasons);
    }

    // 형 변환 실패(바인딩 실패)의 기본 메시지는 내부 타입 이름과 입력 원문을 담으므로 고정 문구로 바꾼다.
    private static String safeFieldMessage(FieldError fieldError) {
        if (fieldError.isBindingFailure() || fieldError.getDefaultMessage() == null) {
            return "유효하지 않은 값입니다.";
        }
        return fieldError.getDefaultMessage();
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidationException(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        if (exception.isForReturnValue()) return handleException(exception, request);
        return error(
                GlobalErrorCode.VALIDATION_ERROR,
                GlobalErrorCode.VALIDATION_ERROR.getMessage(),
                request.getRequestURI(),
                null);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequestException(
            Exception exception, HttpServletRequest request) {
        return error(
                GlobalErrorCode.INVALID_REQUEST,
                GlobalErrorCode.INVALID_REQUEST.getMessage(),
                request.getRequestURI(),
                null);
    }

    @ExceptionHandler({
        HttpRequestMethodNotSupportedException.class,
        HttpMediaTypeNotSupportedException.class,
        org.springframework.web.HttpMediaTypeNotAcceptableException.class,
        org.springframework.web.servlet.resource.NoResourceFoundException.class,
        org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleHttpProtocolException(
            Exception exception, HttpServletRequest request) {
        org.springframework.web.ErrorResponse failure =
                (org.springframework.web.ErrorResponse) exception;
        int status = failure.getStatusCode().value();
        return ResponseEntity.status(status)
                .headers(failure.getHeaders())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(
                        ApiResponse.error(
                                status,
                                "GLOBAL-" + status + "-001",
                                org.springframework.http.HttpStatus.valueOf(status).getReasonPhrase(),
                                request.getRequestURI(),
                                null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(
            Exception exception, HttpServletRequest request) {
        // 트랜잭션 롤백이 끝난 HTTP 예외 처리 경계에서만 제약 위반을 비즈니스 오류 응답으로 변환한다.
        Optional<JobErrorCode> constraintError = findConstraintError(exception);
        if (constraintError.isPresent()) {
            return error(
                    constraintError.get(),
                    constraintError.get().getMessage(),
                    request.getRequestURI(),
                    null);
        }
        log.error(
                "처리하지 못한 서버 오류: type={}, origin={}",
                exception.getClass().getName(),
                exception.getStackTrace().length == 0 ? "unknown" : exception.getStackTrace()[0]);
        return error(
                GlobalErrorCode.INTERNAL_SERVER_ERROR,
                GlobalErrorCode.INTERNAL_SERVER_ERROR.getMessage(),
                request.getRequestURI(),
                null);
    }

    private Optional<JobErrorCode> findConstraintError(Throwable exception) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                Optional<JobErrorCode> errorCode = toConstraintError(violation.getConstraintName());
                if (errorCode.isPresent()) return errorCode;
            }
        }
        return Optional.empty();
    }

    private Optional<JobErrorCode> toConstraintError(String constraintName) {
        if (constraintName == null) return Optional.empty();
        // MySQL이 반환하는 테이블명 접두사를 제거한 뒤 정확한 제약 이름만 비교한다.
        String name = constraintName.substring(constraintName.lastIndexOf('.') + 1);
        return Optional.ofNullable(CONSTRAINT_ERRORS.get(name));
    }

    private ResponseEntity<ApiResponse<Void>> error(
            ErrorCode errorCode, String message, String path, Map<String, Object> reasons) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(
                        ApiResponse.error(
                                errorCode.getHttpStatus().value(), errorCode.getCode(), message, path, reasons));
    }
}
