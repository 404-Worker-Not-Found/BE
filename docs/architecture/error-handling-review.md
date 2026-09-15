# 예외 전달 점검

## 결론

패키지가 다르다는 이유로 IllegalArgumentException/IllegalStateException을 사용할 필요는 없다. 같은 서비스의 호출 스택에서는 BusinessException이 그대로 전파되고 GlobalExceptionHandler가 ErrorCode를 읽어 ApiResponse로 변환한다. 핸들러가 일반 예외에서 업무 의미를 자동으로 복원하지는 않는다.

현재 규칙은 예상 가능한 업무 오류에 도메인 ErrorCode와 커스텀 예외를 사용하는 것이다. 모든 장애를 업무 오류로 바꾸라는 뜻은 아니다.

## 코드에서 확인한 구분

| 위치 | 현재 처리 | 판단 |
| --- | --- | --- |
| work-service WorkApplicationService.fingerprint | SHA-256을 구할 수 없으면 IllegalStateException → 전역 500 | 내부 실행 환경 장애이며 업무 4xx로 바꾸지 않는다. Java 17 표준 알고리즘이므로 정상 환경에서는 지원된다. |
| matching Application.cancel/select/reject 및 Matching.cancel/decline/confirm | IllegalStateException 상태 방어 | 서비스 계층에도 BusinessException 사전 검사가 있다. 엔티티 방어를 API 오류 계약으로 사용할 경우 도메인 코드로 통일해야 한다. 현재 곧바로 모든 사용자 요청이 500이라는 뜻은 아니다. |
| member MemberCommandService, WorkerCommandService, MemberApplicationService | 이메일·전화 중복, 시간·역할 불일치에 IllegalArgumentException | 예상 가능한 업무 오류이므로 도메인 코드로 바꿀 대상이다. 현재는 일반 INVALID_REQUEST로 의미가 합쳐진다. |
| auth VerificationService.sendCode | 전송 간격 제한에 IllegalArgumentException | 재요청 제한을 식별할 도메인 코드와 명확한 HTTP 계약이 적절하다. |
| JWT parser/verifier 및 보안 필터 | 일반 런타임 예외를 필터에서 처리 | MVC 전역 핸들러의 범위 밖이다. 인증 실패와 검증 엔진 장애를 구분하는 전용 예외가 후속 개선 후보다. |
| matching MatchingConfirmationClientException | 단계·HTTP 상태를 보존하고 Saga가 보상/재개 결정 후 BusinessException으로 변환 | 서비스 경계에서 필요한 정보이므로 유지한다. 네트워크·5xx·잘못된 성공 응답은 결과 미확정이다. |
| 설정 검증, 저장된 JSON 손상, Redis 잠금/발행 실패 | 일반 런타임 예외 | 업무 거절과 구분해야 한다. 시작 실패·재시도·복구 경로에서 처리하고 API 입력 오류로 일괄 변환하지 않는다. |

## 권장 전달 방식

1. 같은 서비스의 예상 업무 실패: `throw new BusinessException(DomainErrorCode.X)` → 전역 핸들러 → 상태 코드와 도메인 오류 코드.
2. 외부 서비스 호출 실패: 클라이언트 전용 예외에 단계, 원인, 응답 상태/코드 등 필요한 정보 보존 → 애플리케이션 계층이 재시도/보상 판단 → 자기 서비스의 공개 오류로 변환. 원격 Java 예외 클래스를 공유할 필요는 없다.
3. MVC 입력 바인딩/검증 실패: Bean Validation과 구체적인 Spring 예외를 400으로 매핑. 원본 예외 메시지를 그대로 외부에 보내지 않는다.
4. 필터 인증 실패: AuthenticationEntryPoint/AccessDeniedHandler 또는 현재 내부 필터의 공통 응답 writer를 사용한다. 필터 전체의 모든 예외를 인증 실패로 바꾸면 내부 장애를 숨길 수 있다.
5. 예상 못한 내부 장애: 원인을 보존하고 관측 가능하게 기록하되 응답은 안전한 500. 비밀번호·토큰·인증 헤더는 기록하지 않는다.

도메인을 HTTP에서 완전히 독립시켜야 한다면 도메인 예외와 웹 매핑을 분리할 수 있지만 현재 ErrorCode가 HttpStatus를 포함하는 프로젝트에서는 전면 교체보다 기존 패턴을 일관되게 쓰는 것이 작고 명확하다. 패키지별로 동일 예외를 계속 감싸거나 Result 계층을 새로 도입할 필요는 없다.

이번 chat-service는 위 구분을 적용하고 IllegalArgumentException의 일괄 400 매핑을 두지 않는다. 기존 서비스 전반의 예외 교체는 응답 코드 변경과 외부 클라이언트 호환성 검증을 함께 수행할 후속 작업이다.

## 근거

- [프로젝트 오류 처리 규칙](../agent/coding-rules.md#error-handling-rules)
- [Spring MVC 예외 처리](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/exceptionhandlers.html): DispatcherServlet의 HandlerExceptionResolver와 ControllerAdvice 적용 범위.
- [Java 17 MessageDigest](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/MessageDigest.html): 필수 알고리즘과 getInstance 실패 계약.
