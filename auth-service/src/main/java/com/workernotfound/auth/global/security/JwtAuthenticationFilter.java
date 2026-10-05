package com.workernotfound.auth.global.security;

import com.workernotfound.auth.domain.token.service.AuthTokenClaims;
import com.workernotfound.auth.domain.token.service.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtTokenProvider jwtTokenProvider;
	private final HandlerExceptionResolver exceptionResolver;
    private final com.workernotfound.auth.domain.account.repository.AuthAccountRepository accounts;

	public JwtAuthenticationFilter(
			JwtTokenProvider jwtTokenProvider,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
            com.workernotfound.auth.domain.account.repository.AuthAccountRepository accounts) {
		this.jwtTokenProvider = jwtTokenProvider;
		this.exceptionResolver = exceptionResolver;
        this.accounts = accounts;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String token = resolveToken(request);
		if (token != null) {
			try {
				authenticate(token);
			} catch (InvalidAccessTokenException exception) {
				SecurityContextHolder.clearContext();
			} catch (JwtProcessingException exception) {
				SecurityContextHolder.clearContext();
				if (exceptionResolver.resolveException(request, response, null, exception) == null) {
					throw exception;
				}
				return;
			}
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
        if (accounts.findById(claims.authAccountId()).filter(account -> account.getStatus() != com.workernotfound.auth.domain.account.entity.enums.MemberStatus.WITHDRAWN
                && account.getMemberId().equals(claims.memberId())).isEmpty()) throw new InvalidAccessTokenException("탈퇴한 계정입니다.");
		List<SimpleGrantedAuthority> authorities =
				List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name()));
		UsernamePasswordAuthenticationToken authentication =
				new UsernamePasswordAuthenticationToken(claims, null, authorities);
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}
}
