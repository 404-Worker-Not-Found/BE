package com.workernotfound.member.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.member.global.exception.GlobalErrorCode;
import com.workernotfound.member.global.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class InternalSecretAuthenticationFilter extends OncePerRequestFilter {

	private static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";

	private final InternalApiProperties internalApiProperties;
	private final ObjectMapper objectMapper;

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		if (!isInternalRequest(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		if (!hasValidInternalSecret(request)) {
			writeUnauthorized(response, request);
			return;
		}

		filterChain.doFilter(request, response);
	}

	private boolean isInternalRequest(HttpServletRequest request) {
		String path = request.getRequestURI();
		return path.equals("/internal")
			|| path.startsWith("/internal/")
			|| path.equals("/api/members/internal")
			|| path.startsWith("/api/members/internal/");
	}

	private boolean hasValidInternalSecret(HttpServletRequest request) {
		String configuredSecret = internalApiProperties.secret();
		String requestSecret = request.getHeader(INTERNAL_SECRET_HEADER);
		if (!StringUtils.hasText(configuredSecret) || !StringUtils.hasText(requestSecret)) {
			return false;
		}
		return MessageDigest.isEqual(toBytes(configuredSecret), toBytes(requestSecret));
	}

	private byte[] toBytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private void writeUnauthorized(HttpServletResponse response, HttpServletRequest request) throws IOException {
		GlobalErrorCode errorCode = GlobalErrorCode.UNAUTHORIZED;
		response.setStatus(errorCode.getHttpStatus().value());
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		ApiResponse<Void> body = ApiResponse.error(
			errorCode.getHttpStatus().value(),
			errorCode.getCode(),
			"유효하지 않은 내부 API secret입니다.",
			request.getRequestURI(),
			null
		);
		objectMapper.writeValue(response.getWriter(), body);
	}
}
