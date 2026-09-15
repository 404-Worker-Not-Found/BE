# 예외 전달과 오류 응답 계약

## 원칙

같은 서비스에서는 예상 가능한 업무 오류를 `BusinessException + 도메인 ErrorCode`로 전달한다. 패키지가 달라져도 예외를 다시 감쌀 필요는 없다. 전역 핸들러는 예외가 가진 코드·HTTP 상태를 공통 `ApiResponse`로 바꾼다.

입력 변환 오류, 업무 거절, 인증 실패, 서버 장애, 원격 요청 결과 미확정을 구분한다. 일반 IllegalArgumentException을 모두 400으로 바꾸거나 RuntimeException을 모두 인증 실패로 숨기지 않는다.

## 현재 서비스별 처리

| 범위 | 처리 |
| --- | --- |
| member 이메일·전화 중복 | `MEMBER-409-001`, `MEMBER-409-002` |
| member 가입 역할 불일치 | `MEMBER-400-001` |
| member 회원·활성 회원 조회 실패 | `MEMBER-404-001`, `MEMBER-404-002` |
| owner/worker 프로필 조회 실패 | `OWNER-404-001`, `WORKER-404-001` |
| worker 선호 업종 중복·근무 시간 오류 | `WORKER-400-001`, `WORKER-400-002` |
| auth 로그인 실패·비활성 계정 | `AUTH-401-001`, `AUTH-401-002` |
| auth 이메일·휴대폰 미인증, 잘못된 가입 티켓 | `AUTH-400-001`~`003` |
| auth 이메일·OAuth 연결 중복 | `AUTH-409-001`, `AUTH-409-002` |
| auth 인증번호 재전송 제한 | `AUTH-429-001` |
| auth refresh token 없음·폐기·만료·비활성 계정·기기 불일치 | `TOKEN-401-001`~`005` |
| matching 지원·매칭의 종료 상태 재변경 | `APPLICATION-409-002`, `MATCHING-409-003` |
| work/chat | 기존 도메인 코드 유지, 공통 HTTP 오류 경계 정리 |

회원 중복·조회 오류, 인증 중복·전송 제한 등은 기존 일반 400 응답에서 409·404·429로 달라진다. 로그인·refresh token은 401을 유지하며 코드를 구체화한다. 클라이언트는 원문 메시지 대신 `code`를 사용해야 한다.

위 중복 오류는 현재 서비스의 중복 검사에서 반환하는 계약이다. 선조회 이후 발생한 DB 경쟁이나 예상하지 못한 제약 위반을 모두 업무 중복으로 간주하지는 않는다.

## 전역 핸들러

- 업무 오류: 도메인 코드와 상태, 명시적으로 제공한 검증 사유를 반환한다.
- Spring MVC 요청 검증·JSON·헤더·타입 변환 실패: 안전한 400 응답. 원본 바인딩 예외 메시지를 노출하지 않는다.
- HTTP 메서드·미디어 타입·리소스 오류: 405·415·406·404 및 표준 응답 헤더를 보존한다. 코드는 `GLOBAL-{status}-001`이다.
- 반환값 검증 실패 및 예상하지 못한 내부 장애: 안전한 500. 로그에는 예외 유형과 발생 위치를 기록하며 요청·토큰·예외 원문의 민감 정보를 넣지 않는다.

전역 MVC 핸들러는 스케줄러·이벤트 리스너·서버 시작 단계의 오류를 처리하지 않는다. 해당 경계에서는 기존 복구·재시도·시작 실패 처리를 유지한다.

## JWT와 보안 필터

`auth`, `member`, `job`, `matching`은 `InvalidAccessTokenException`과 `JwtProcessingException`을 구분한다. 형식·만료·필수 claim·역할 오류는 전자, 서명 엔진 장애는 후자다. validateAccessToken은 잘못된 토큰에 대해서만 false를 반환하고 내부 장애는 전파한다.

필터는 토큰을 한 번만 파싱한다. 잘못된 토큰은 인증 컨텍스트를 비우고 기존 익명 요청 정책을 따른다. 보호된 API는 Security의 인증 진입점에서 401을 반환하고 공개 API의 기존 접근 정책은 유지한다. 엔진 장애는 명시적으로 MVC resolver에 전달해 안전한 500 envelope를 쓰며, resolver가 처리하지 못하면 예외를 다시 던진다. 처리 대상을 찾지 못했는데 빈 200을 반환하지 않는다.

내부 secret 인증 실패는 기존 필터의 401 응답을 유지한다. DB/HTTP/암호화 장애를 무조건 인증 실패로 바꾸지 않는다.

## 서비스 간 오류 전달

- auth의 member 클라이언트는 알려진 회원·근로자 입력 오류와 기존 사업자 검증 오류를 HTTP 상태와 코드가 모두 맞을 때만 해석한다. 원문 메시지를 신뢰하거나 알 수 없는 원격 코드를 그대로 공개하지 않는다.
- 알려지지 않은 응답이나 잘못된 성공 응답, 네트워크 오류는 기존 외부 API 오류로 남긴다.
- matching은 member 조회 404를 지원 자격 없음으로 처리한다. 다른 서비스 장애는 의존 서비스 오류다.
- `MatchingConfirmationClientException`의 단계·상태·원인은 보존한다. Saga는 명확한 거절과 네트워크·5xx·잘못된 성공 응답에 따른 결과 미확정을 구분해 보상 또는 같은 명령 재시도를 결정한다. 이 구분을 단일 업무 예외로 일찍 합치지 않는다.

원격 서비스의 Java 예외 클래스를 공유할 필요는 없다. 업무 계층에서 결과를 해석한 뒤 자기 서비스의 공개 응답으로 변환하는 기존 구조를 유지한다.

## 일반 런타임 예외를 유지하는 곳

SHA-256 초기화 실패, OAuth 가입 티켓의 저장·역직렬화 실패, 저장된 JSON 손상, Redis 잠금·발행 실패, 서버 설정 검증, 내부 점수 계산 인자·상태 불변식은 업무 4xx로 바꾸지 않는다. OAuth 티켓 직렬화 오류는 원인을 보존한 서버 장애로 수정했다. 종료된 지원·매칭을 다시 바꾸려는 업무 규칙 위반과 내부 계산기의 잘못된 호출은 구분한다.

## 근거

- [프로젝트 오류 처리 규칙](../agent/coding-rules.md#error-handling-rules)
- [Spring MVC 예외 처리](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/exceptionhandlers.html)
- [Java 17 MessageDigest](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/MessageDigest.html)
