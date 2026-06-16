package com.workernotfound.auth.global.security;

import com.workernotfound.auth.domain.token.service.AuthTokenClaims;
import com.workernotfound.auth.domain.token.service.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtTokenProvider jwtTokenProvider;

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String token = resolveToken(request);
		if (token != null && jwtTokenProvider.validateAccessToken(token)) {
			authenticate(token);
		}
		filterChain.doFilter(request, response);
	}

	private String resolveToken(HttpServletRequest request) {
		String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);
		if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
			return null;
		}
		return authorizationHeader.substring(BEARER_PREFIX.length());
	}

	private void authenticate(String token) {
		AuthTokenClaims claims = jwtTokenProvider.parseAccessToken(token);
		List<SimpleGrantedAuthority> authorities = List.of(
			new SimpleGrantedAuthority("ROLE_" + claims.role().name())
		);
		UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
			claims,
			null,
			authorities
		);
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}
}
