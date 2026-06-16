package com.workernotfound.member.global.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(InternalApiProperties.class)
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(
		HttpSecurity http,
		InternalSecretAuthenticationFilter internalSecretAuthenticationFilter
	) throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(
					"/api/members/internal/**",
					"/api/members/me",
					"/v3/api-docs/**",
					"/swagger-ui/**",
					"/swagger-ui.html",
					"/error"
				).permitAll()
				.anyRequest().authenticated()
			)
			// TODO: API Gateway나 mTLS 기반 서비스 간 인증으로 대체한다.
			.addFilterBefore(internalSecretAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
			.build();
	}

	@Bean
	public InternalSecretAuthenticationFilter internalSecretAuthenticationFilter(
		InternalApiProperties internalApiProperties
	) {
		return new InternalSecretAuthenticationFilter(internalApiProperties);
	}
}
