package com.workernotfound.payment.global.exception;

import com.workernotfound.payment.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
                    fieldError ->
                        fieldError.getDefaultMessage() == null
                            ? "유효하지 않은 값입니다."
                            : fieldError.getDefaultMessage(),
                    (first, second) -> first));
    return error(
        GlobalErrorCode.VALIDATION_ERROR,
        GlobalErrorCode.VALIDATION_ERROR.getMessage(),
        request.getRequestURI(),
        reasons);
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

  private ResponseEntity<ApiResponse<Void>> error(
      ErrorCode errorCode, String message, String path, Map<String, Object> reasons) {
    return ResponseEntity.status(errorCode.getHttpStatus())
        .body(
            ApiResponse.error(
                errorCode.getHttpStatus().value(), errorCode.getCode(), message, path, reasons));
  }
}
