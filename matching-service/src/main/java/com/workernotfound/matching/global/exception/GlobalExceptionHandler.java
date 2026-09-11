package com.workernotfound.matching.global.exception;

import com.workernotfound.matching.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusinessException(
		BusinessException exception,
		HttpServletRequest request
	) {
		return error(exception.getErrorCode(), exception.getMessage(), request.getRequestURI(), null);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidationException(
		MethodArgumentNotValidException exception,
		HttpServletRequest request
	) {
		Map<String, Object> reasons = exception.getBindingResult().getFieldErrors().stream()
			.collect(Collectors.toMap(
				fieldError -> fieldError.getField(),
				fieldError -> fieldError.getDefaultMessage() == null
					? "유효하지 않은 값입니다."
					: fieldError.getDefaultMessage(),
				(first, second) -> first
			));
		return error(
			GlobalErrorCode.VALIDATION_ERROR,
			GlobalErrorCode.VALIDATION_ERROR.getMessage(),
			request.getRequestURI(),
			reasons
		);
	}

	@ExceptionHandler({
		IllegalArgumentException.class,
		HttpMessageNotReadableException.class,
		MethodArgumentTypeMismatchException.class,
		MissingServletRequestParameterException.class
	})
	public ResponseEntity<ApiResponse<Void>> handleInvalidRequestException(
		Exception exception,
		HttpServletRequest request
	) {
		return error(GlobalErrorCode.INVALID_REQUEST, exception.getMessage(), request.getRequestURI(), null);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(
		Exception exception,
		HttpServletRequest request
	) {
		return error(
			GlobalErrorCode.INTERNAL_SERVER_ERROR,
			GlobalErrorCode.INTERNAL_SERVER_ERROR.getMessage(),
			request.getRequestURI(),
			null
		);
	}

	private ResponseEntity<ApiResponse<Void>> error(
		ErrorCode errorCode,
		String message,
		String path,
		Map<String, Object> reasons
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
