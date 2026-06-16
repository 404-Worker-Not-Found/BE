package com.workernotfound.auth.global.response;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;

public record ApiResponse<T>(
	boolean success,
	int status,
	String code,
	String message,
	T data,
	String path,
	LocalDateTime timestamp,
	Map<String, Object> reasons
) {

	private static final String SUCCESS_CODE = "SUCCESS";
	private static final String SUCCESS_MESSAGE = "요청이 성공적으로 처리되었습니다.";

	public static <T> ApiResponse<T> success(T data) {
		return success(HttpStatus.OK, data);
	}

	public static <T> ApiResponse<T> success(HttpStatus status, T data) {
		return new ApiResponse<>(
			true,
			status.value(),
			SUCCESS_CODE,
			SUCCESS_MESSAGE,
			data,
			null,
			LocalDateTime.now(),
			null
		);
	}

	public static ApiResponse<Void> error(
		int status,
		String code,
		String message,
		String path,
		Map<String, Object> reasons
	) {
		return new ApiResponse<>(
			false,
			status,
			code,
			message,
			null,
			path,
			LocalDateTime.now(),
			reasons
		);
	}
}
