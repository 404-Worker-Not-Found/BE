package com.workernotfound.auth.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.auth.global.exception.GlobalErrorCode;
import com.workernotfound.auth.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final ObjectMapper objectMapper;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exception -> exception
				.authenticationEntryPoint((request, response, authException) ->
					writeSecurityError(response, GlobalErrorCode.UNAUTHORIZED, request)
				)
				.accessDeniedHandler((request, response, accessDeniedException) ->
					writeSecurityError(response, GlobalErrorCode.FORBIDDEN, request)
				)
			)
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(
					"/api/auth/signup/**",
					"/api/auth/login",
					"/api/auth/logout",
					"/api/auth/oauth2/**",
					"/api/auth/tokens/reissue",
					"/api/auth/email-verifications/**",
					"/api/auth/sms-verifications/**",
					"/v3/api-docs/**",
					"/swagger-ui/**",
					"/swagger-ui.html",
					"/error"
				).permitAll()
				.anyRequest().authenticated()
			)
			.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
			.build();
	}

	private void writeSecurityError(
		HttpServletResponse response,
		GlobalErrorCode errorCode,
		HttpServletRequest request
	) throws IOException {
		response.setStatus(errorCode.getHttpStatus().value());
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		ApiResponse<Void> body = ApiResponse.error(
			errorCode.getHttpStatus().value(),
			errorCode.getCode(),
			errorCode.getMessage(),
			request.getRequestURI(),
			null
		);
		objectMapper.writeValue(response.getWriter(), body);
	}
}
