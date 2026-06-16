package com.workernotfound.member.global.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(
					"/v3/api-docs/**",
					"/swagger-ui/**",
					"/swagger-ui.html"
				).permitAll()
				.anyRequest().authenticated()
			)
			.build();
	}
}
