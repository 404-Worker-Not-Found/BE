package com.workernotfound.job.global.exception;

import com.workernotfound.job.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException e, HttpServletRequest request
    ) {
        return error(e.getErrorCode(), e.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException e, HttpServletRequest request
    ) {
        Map<String, Object> reasons = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        fe -> fe.getField(),
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "유효하지 않은 값입니다.",
                        (existing, replacement) -> existing
                ));
        return error(GlobalErrorCode.VALIDATION_ERROR, GlobalErrorCode.VALIDATION_ERROR.getMessage(), request.getRequestURI(), reasons);
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(
            Exception e, HttpServletRequest request
    ) {
        return error(GlobalErrorCode.INVALID_REQUEST, e.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(
            Exception e, HttpServletRequest request
    ) {
        return error(GlobalErrorCode.INTERNAL_SERVER_ERROR, GlobalErrorCode.INTERNAL_SERVER_ERROR.getMessage(), request.getRequestURI(), null);
    }

    private ResponseEntity<ApiResponse<Void>> error(
            ErrorCode errorCode, String message, String path, Map<String, Object> reasons
    ) {
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.error(
                        errorCode.getHttpStatus().value(),
                        errorCode.getCode(),
                        message,
                        path,
                        reasons
                ));
    }
}
