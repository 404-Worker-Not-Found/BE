package com.workernotfound.work.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.work.global.exception.GlobalErrorCode;
import com.workernotfound.work.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableConfigurationProperties({InternalApiProperties.class, JwtProperties.class})
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      InternalApiProperties internalApiProperties,
      ObjectMapper objectMapper,
      JwtAuthenticationFilter jwtAuthenticationFilter)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            exception ->
                exception
                    .authenticationEntryPoint(
                        (request, response, authException) ->
                            writeSecurityError(
                                response, GlobalErrorCode.UNAUTHORIZED, request, objectMapper))
                    .accessDeniedHandler(
                        (request, response, accessDeniedException) ->
                            writeSecurityError(
                                response, GlobalErrorCode.FORBIDDEN, request, objectMapper)))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/works/internal/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/error")
                    .permitAll()
                    .requestMatchers("/api/works/**")
                    .hasAnyRole("OWNER", "WORKER")
                    .anyRequest()
                    .denyAll())
        // TODO: API Gateway나 mTLS 기반 서비스 간 인증으로 대체한다.
        .addFilterBefore(
            internalSecretAuthenticationFilter(internalApiProperties, objectMapper),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  private InternalSecretAuthenticationFilter internalSecretAuthenticationFilter(
      InternalApiProperties internalApiProperties, ObjectMapper objectMapper) {
    return new InternalSecretAuthenticationFilter(internalApiProperties, objectMapper);
  }

  private void writeSecurityError(
      HttpServletResponse response,
      GlobalErrorCode errorCode,
      HttpServletRequest request,
      ObjectMapper objectMapper)
      throws IOException {
    response.setStatus(errorCode.getHttpStatus().value());
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    ApiResponse<Void> body =
        ApiResponse.error(
            errorCode.getHttpStatus().value(),
            errorCode.getCode(),
            errorCode.getMessage(),
            request.getRequestURI(),
            null);
    objectMapper.writeValue(response.getWriter(), body);
  }
}
