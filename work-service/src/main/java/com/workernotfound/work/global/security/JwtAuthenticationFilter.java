package com.workernotfound.work.global.security;

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

  private final JwtTokenParser jwtTokenParser;
    private final com.workernotfound.work.global.account.AccountGateService accountGates;
  private final HandlerExceptionResolver exceptionResolver;

  public JwtAuthenticationFilter(
      JwtTokenParser jwtTokenParser,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
            com.workernotfound.work.global.account.AccountGateService accountGates) {
    this.jwtTokenParser = jwtTokenParser;
    this.exceptionResolver = exceptionResolver;
        this.accountGates = accountGates;
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
    AuthenticatedMember member = jwtTokenParser.parseAccessToken(token);
        if (!accountGates.isActive(member.memberId())) throw new InvalidAccessTokenException("탈퇴 처리 중이거나 탈퇴한 회원입니다.");
    List<SimpleGrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("ROLE_" + member.role()));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(member, null, authorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
