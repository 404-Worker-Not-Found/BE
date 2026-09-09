# Auth and Member Signup Domain Design

이 문서는 `auth-service`와 `member-service`의 인증/회원가입 도메인 설계를 정리합니다.

목적은 구현 전 참고용 설계 기록입니다. AI 에이전트 행동 규칙이 아니며, 구현 시점의 세부 기술 선택이나 API 스펙은 변경될 수 있습니다.

## 전제

- Java 17과 Spring Boot를 사용한다.
- `auth-service`와 `member-service`는 별도 애플리케이션이다.
- 초기 서비스 간 통신은 REST API를 사용한다.
- 각 서비스는 자기 데이터베이스 경계를 소유한다.
- 서비스 간 참조는 물리 FK가 아니라 외부 ID로 저장한다.
- 공통 엔티티 상속용 `BaseTimeEntity`와 `BaseEntity`를 사용한다.
- `BaseTimeEntity`는 `createdAt`, `updatedAt`을 가진다.
- `BaseEntity`는 `id`를 가진다.
- 각 엔티티는 `BaseEntity`를 `extends`한다.

## 회원가입 정책

- `LOCAL` 회원가입을 허용한다.
- OAuth2 provider는 `KAKAO`, `NAVER`만 사용한다.
- 회원 역할은 `OWNER`, `WORKER`만 사용한다.
- 가입 후 역할 변경은 불가능하다.
- 회원 상태는 `ACTIVE`, `WITHDRAWN`, `BLOCKED`만 사용한다.
- `PENDING` 상태는 사용하지 않는다.
- 회원가입은 온보딩 후완료 방식이 아니다.
- 이름, 이메일, 비밀번호, 휴대폰 인증, 역할별 추가 정보를 모두 입력한 뒤 최종 가입된다.
- 이메일 인증은 필수다.
- 휴대폰 인증은 SMS 인증번호 입력 방식으로 한다.
- 인증번호는 DB 테이블이 아니라 Redis에 TTL 기반으로 저장한다.

## 토큰 정책

- JWT access token과 refresh token을 사용한다.
- refresh token rotation 정책을 사용한다.
- access token 재발급 시 기존 refresh token은 폐기하고 새 refresh token을 발급한다.
- refresh token은 DB에 원문 저장하지 않고 hash로 저장한다.
- refresh token에는 `deviceId`를 함께 저장한다.

## 서비스 책임

### auth-service

- LOCAL 로그인
- KAKAO OAuth2 로그인
- NAVER OAuth2 로그인
- 이메일 인증번호 발송/검증
- 휴대폰 인증번호 발송/검증
- OWNER 회원가입
- WORKER 회원가입
- access token 재발급
- 로그아웃 시 refresh token 폐기
- 비밀번호 BCrypt 암호화
- 인증 완료 여부 관리

### member-service

- Member 기본 정보 저장
- OWNER 프로필 저장
- WORKER 프로필 저장
- 위치 정보 저장
- 내 회원 정보 조회

## auth-service 패키지 구조

```text
com.workernotfound.auth
├── AuthServiceApplication.java
├── global
│   ├── config
│   ├── exception
│   ├── response
│   └── security
│       ├── jwt
│       └── oauth2
├── domain
│   ├── account
│   │   ├── controller
│   │   ├── service
│   │   ├── repository
│   │   ├── entity
│   │   └── dto
│   │       ├── request
│   │       └── response
│   ├── auth
│   │   ├── controller
│   │   │   └── docs
│   │   ├── service
│   │   ├── entity
│   │   └── dto
│   │       ├── request
│   │       └── response
│   ├── token
│   │   ├── controller
│   │   │   └── docs
│   │   ├── service
│   │   ├── repository
│   │   ├── entity
│   │   └── dto
│   │       ├── request
│   │       └── response
│   └── common
│       └── entity
└── external
    ├── client
    │   ├── member
    │   └── oauth
    ├── event
    └── redis
```

## member-service 패키지 구조

```text
com.workernotfound.member
├── MemberServiceApplication.java
├── global
│   ├── config
│   ├── exception
│   ├── response
│   └── security
├── domain
│   ├── member
│   │   ├── controller
│   │   ├── service
│   │   ├── repository
│   │   ├── entity
│   │   └── dto
│   │       ├── request
│   │       └── response
│   ├── owner
│   │   ├── service
│   │   ├── repository
│   │   └── entity
│   ├── worker
│   │   ├── service
│   │   ├── repository
│   │   └── entity
│   └── location
│       ├── service
│       ├── repository
│       ├── entity
│       └── dto
└── external
    ├── client
    └── event
```

## auth-service 엔티티

### AuthAccount

인증 계정의 중심 엔티티다.

필드:

- `memberId`: `Long`, member-service의 Member ID
- `email`: `String`, unique
- `role`: `MemberRole`
- `status`: `MemberStatus`
- `signupType`: `SignupType`
- `lastLoginAt`: `LocalDateTime`

주의:

- `email`이 같으면 같은 회원의 인증 계정으로 보고 연결한다.
- 한 회원이 LOCAL, KAKAO, NAVER를 함께 연결할 수 있다.
- `memberId`는 회원 단위 `AuthAccount`와 1:1로 연결되는 값으로 본다.

### LocalCredential

LOCAL 로그인용 비밀번호 인증 정보를 저장한다.

필드:

- `authAccountId`: `Long`, auth-service 내부 FK, unique
- `passwordHash`: `String`
- `passwordChangedAt`: `LocalDateTime`

주의:

- `email`은 저장하지 않는다.
- 이메일은 `AuthAccount.email`을 원천으로 사용한다.

### OAuthConnection

OAuth2 provider와 인증 계정의 연결 정보를 저장한다.

필드:

- `authAccountId`: `Long`, auth-service 내부 FK
- `provider`: `OAuthProvider`
- `providerUserId`: `String`
- `providerEmail`: `String`
- `connectedAt`: `LocalDateTime`

제약:

- `provider + providerUserId` unique

### RefreshToken

refresh token rotation과 로그아웃 폐기를 위한 저장소다.

필드:

- `authAccountId`: `Long`, auth-service 내부 FK
- `memberId`: `Long`, member-service 외부 ID
- `deviceId`: `String`
- `tokenHash`: `String`
- `expiresAt`: `LocalDateTime`
- `revokedAt`: `LocalDateTime`, nullable
- `replacedByTokenId`: `Long`, nullable
- `lastUsedAt`: `LocalDateTime`

제약 후보:

- `tokenHash` unique
- `authAccountId + deviceId` index

## member-service 엔티티

### Member

회원 기본 정보를 저장한다.

필드:

- `name`: `String`
- `email`: `String`, unique
- `phoneNumber`: `String`, unique
- `role`: `MemberRole`
- `status`: `MemberStatus`
- `joinedAt`: `LocalDateTime`
- `withdrawnAt`: `LocalDateTime`, nullable
- `blockedAt`: `LocalDateTime`, nullable

주의:

- 이메일/휴대폰 인증 여부의 원천 책임은 auth-service에 둔다.
- 회원 정보 화면에서 "인증된 이메일", "인증된 휴대폰" 표시가 필요하면 member-service에 복사 필드를 둘 수 있다.
- 복사 필드를 둘 경우 원천 데이터가 아니라 표시용 스냅샷으로 명시한다.

### OwnerProfile

OWNER 역할의 추가 정보를 저장한다.

필드:

- `memberId`: `Long`, member-service 내부 FK, unique
- `businessRegistrationNumber`: `String`
- `storeName`: `String`
- `businessType`: `String`
- `businessVerificationStatus`: `BusinessVerificationStatus`
- `storeLocationId`: `Long`, member-service 내부 FK

의미:

- `businessVerificationStatus`만 사업자 검증 상태의 원천으로 사용한다.
- 검증 완료 여부 판단이 필요하면 `businessVerificationStatus == VERIFIED`로 확인한다.

### WorkerProfile

WORKER 역할의 추가 정보를 저장한다.

필드:

- `memberId`: `Long`, member-service 내부 FK, unique
- `desiredHourlyWage`: `Integer`
- `activityRadiusKm`: `Integer`
- `immediatelyAvailable`: `boolean`
- `baseLocationId`: `Long`, member-service 내부 FK

주의:

- `preferredBusinessType` 단일 문자열 필드는 사용하지 않는다.
- `availableTimeSlots` 문자열 필드는 사용하지 않는다.
- 선호 업종과 가능 근무 시간은 별도 엔티티로 저장한다.

### WorkerPreferredBusinessType

WORKER의 선호 업종 목록을 저장한다.

필드:

- `workerProfileId`: `Long`, member-service 내부 FK
- `businessType`: `String`

제약:

- `workerProfileId + businessType` unique

### WorkerAvailableTime

WORKER의 가능 근무 시간대를 저장한다.

필드:

- `workerProfileId`: `Long`, member-service 내부 FK
- `dayOfWeek`: `DayOfWeek`
- `startTime`: `LocalTime`
- `endTime`: `LocalTime`

검증 후보:

- `startTime < endTime`
- 같은 `workerProfileId` 내에서 중복 또는 겹치는 시간대 방지

### Location

주소와 좌표를 함께 저장한다.

필드:

- `address`: `String`
- `detailAddress`: `String`, nullable
- `latitude`: `BigDecimal`
- `longitude`: `BigDecimal`

정책:

- 사용자에게 보여줄 때는 주소를 사용한다.
- 추후 반경 몇 km 내 공고 조회 기능에서는 위도/경도를 사용한다.

## enum

### MemberRole

```text
OWNER
WORKER
```

### MemberStatus

```text
ACTIVE
WITHDRAWN
BLOCKED
```

### OAuthProvider

```text
KAKAO
NAVER
```

### SignupType

```text
LOCAL
OAUTH
```

### VerificationPurpose

```text
SIGNUP
PASSWORD_RESET
```

### VerificationChannel

```text
EMAIL
SMS
```

### BusinessVerificationStatus

```text
NOT_VERIFIED
VERIFIED
FAILED
```

## Redis key 설계

### 이메일 인증번호

```text
key: auth:verification:email:{purpose}:{email}
value: hashedCode 또는 code
ttl: 5분
```

### 이메일 인증 성공

```text
key: auth:verification:email:verified:{purpose}:{email}
value: true
ttl: 30분
```

### SMS 인증번호

```text
key: auth:verification:sms:{purpose}:{phoneNumber}
value: hashedCode 또는 code
ttl: 3분
```

### SMS 인증 성공

```text
key: auth:verification:sms:verified:{purpose}:{phoneNumber}
value: true
ttl: 30분
```

### OAuth 회원가입 임시 티켓

```text
key: auth:oauth2:signup-ticket:{ticketId}
value: provider, providerUserId, providerEmail, issuedAt
ttl: 30분
```

- 티켓의 유효기간은 최초 발급 시각부터 30분으로 고정한다.
- 재시도 가능한 가입 거절로 티켓을 복구할 때는 `issuedAt`을 기준으로 남은 TTL만 설정한다.
- 최초 만료 시각이 지난 티켓은 복구하지 않는다.

### 인증번호 발송 제한

```text
key: auth:verification:email:send-limit:{purpose}:{email}
key: auth:verification:sms:send-limit:{purpose}:{phoneNumber}
value: 1
ttl: 1분
```

### 인증번호 검증 시도 제한

```text
key: auth:verification:email:attempts:{purpose}:{email}
key: auth:verification:sms:attempts:{purpose}:{phoneNumber}
value: failedAttemptCount
ttl: 인증번호 TTL과 동일
```

주의:

- refresh token은 Redis가 아니라 DB에 hash로 저장한다.
- Redis는 인증번호, 인증 성공 플래그, OAuth 임시 티켓, 발송 제한, 검증 시도 제한에 사용한다.

## API 목록

### auth-service

```text
POST /api/auth/signup/owner
```

OWNER LOCAL 회원가입

```text
POST /api/auth/signup/worker
```

WORKER LOCAL 회원가입

```text
POST /api/auth/login
```

LOCAL 로그인

```text
POST /api/auth/oauth2/{provider}/login
```

OAuth2 인가 코드 기반 로그인. `provider`는 `KAKAO` 또는 `NAVER`를 사용한다.

```text
POST /api/auth/oauth2/signup/owner
POST /api/auth/oauth2/signup/worker
```

OAuth signup ticket 기반 OWNER/WORKER 회원가입 완료

```text
POST /api/auth/email-verifications/send
POST /api/auth/email-verifications/verify
```

이메일 인증번호 발송/검증

```text
POST /api/auth/sms-verifications/send
POST /api/auth/sms-verifications/verify
```

휴대폰 인증번호 발송/검증

```text
POST /api/auth/tokens/reissue
```

access token 재발급과 refresh token rotation

```text
POST /api/auth/logout
```

refresh token 폐기

### member-service

```text
POST /api/members/internal/owners
```

auth-service에서 OWNER 회원 생성 요청

```text
POST /api/members/internal/workers
```

auth-service에서 WORKER 회원 생성 요청

```text
GET /api/members/me
```

내 회원 정보 조회

```text
GET /api/members/internal/{memberId}
```

서비스 간 회원 기본 정보 조회

```text
DELETE /api/members/internal/{memberId}
```

auth-service 회원가입 실패 보상용 회원 삭제

주의:

- `*/internal/**` 용도의 API도 URL은 `/api/**` 하위에 둔다.
- 내부 API는 외부 클라이언트가 직접 호출하지 못하도록 gateway/security 정책으로 제한한다.
- 초기에는 REST로 호출한다.
- 추후 필요하면 이벤트 기반 복제나 비동기 상태 전파를 추가한다.

## LOCAL 회원가입 흐름

1. 클라이언트가 이메일 인증번호 발송을 요청한다.
2. auth-service가 이메일 인증번호를 Redis에 TTL로 저장하고 발송한다.
3. 클라이언트가 이메일 인증번호 검증을 요청한다.
4. auth-service가 검증 성공 플래그를 Redis에 TTL로 저장한다.
5. 클라이언트가 SMS 인증번호 발송을 요청한다.
6. auth-service가 SMS 인증번호를 Redis에 TTL로 저장하고 발송한다.
7. 클라이언트가 SMS 인증번호 검증을 요청한다.
8. auth-service가 검증 성공 플래그를 Redis에 TTL로 저장한다.
9. 클라이언트가 이름, 이메일, 비밀번호, 휴대폰 번호, `deviceId`, 역할별 추가 정보를 포함해 최종 회원가입을 요청한다.
10. auth-service가 이메일/휴대폰 인증 완료 여부를 Redis에서 확인한다.
11. auth-service가 기존 인증 계정의 중복 여부를 사전 확인한다.
12. auth-service가 DB 트랜잭션 밖에서 member-service 내부 API를 호출해 Member와 역할별 프로필 생성을 요청한다.
13. member-service가 회원 기본 정보, 역할별 프로필, 위치 정보를 자기 DB 트랜잭션으로 저장한다.
14. member-service가 생성된 `memberId`를 응답한다.
15. auth-service가 자기 DB 트랜잭션을 시작하고 중복 여부를 다시 확인한다.
16. auth-service가 비밀번호를 BCrypt로 암호화하고 `AuthAccount`, `LocalCredential`을 저장한다.
17. auth-service가 access token과 refresh token을 발급하고 refresh token hash와 `deviceId`를 저장한다.
18. auth-service는 트랜잭션을 종료하고 refresh token 원문을 클라이언트에만 반환한다.

## OAuth2 로그인 및 가입 흐름

1. 클라이언트가 KAKAO 또는 NAVER 로그인을 시작한다.
2. auth-service가 provider 인증을 완료하고 `providerUserId`를 조회한다.
3. 기존 `OAuthConnection`이 있으면 로그인 처리한다.
4. 기존 `OAuthConnection`이 없지만 provider 이메일과 같은 `AuthAccount.email`이 있으면 해당 인증 계정에 OAuth 연결을 추가한다.
5. 같은 이메일의 `AuthAccount`가 없으면 즉시 회원을 만들지 않고 OAuth signup ticket을 Redis에 저장한다.
6. 클라이언트가 ticket과 함께 역할별 추가 정보, 휴대폰 인증, 필요한 기본 정보를 제출한다.
7. auth-service가 DB 트랜잭션 밖에서 member-service에 회원 생성을 요청한다.
8. auth-service가 자기 DB 트랜잭션에서 중복 여부를 다시 확인하고 `AuthAccount`, `OAuthConnection`을 저장한다.
9. auth-service가 같은 트랜잭션에서 access token과 refresh token을 발급하고 refresh token hash를 저장한다.
10. 재시도 가능한 점주 사업자 검증 오류가 발생하면 OAuth signup ticket을 최초 만료 시각까지 남은 TTL로 복구한다.

현재 구현은 Spring Security OAuth2 redirect login이 아니라, 클라이언트가 받은 provider 인가 코드를 `POST /api/auth/oauth2/{provider}/login`으로 전달하면 auth-service가 provider token/userinfo API를 호출하는 방식이다.

## auth-service to member-service DTO

### CreateOwnerMemberRequest

```java
public record CreateOwnerMemberRequest(
    String name,
    String email,
    String phoneNumber,
    MemberRole role,
    String businessRegistrationNumber,
    String storeName,
    String businessType,
    LocationRequest storeLocation
) {
}
```

### CreateWorkerMemberRequest

```java
public record CreateWorkerMemberRequest(
    String name,
    String email,
    String phoneNumber,
    MemberRole role,
    Integer desiredHourlyWage,
    Integer activityRadiusKm,
    boolean immediatelyAvailable,
    LocationRequest baseLocation,
    List<String> preferredBusinessTypes,
    List<WorkerAvailableTimeRequest> availableTimes
) {
}
```

### WorkerAvailableTimeRequest

```java
public record WorkerAvailableTimeRequest(
    DayOfWeek dayOfWeek,
    LocalTime startTime,
    LocalTime endTime
) {
}
```

### LocationRequest

```java
public record LocationRequest(
    String address,
    String detailAddress,
    BigDecimal latitude,
    BigDecimal longitude
) {
}
```

### CreateMemberResponse

```java
public record CreateMemberResponse(
    Long memberId,
    String email,
    String phoneNumber,
    MemberRole role,
    MemberStatus status
) {
}
```

### MemberInternalResponse

```java
public record MemberInternalResponse(
    Long memberId,
    String name,
    String email,
    String phoneNumber,
    MemberRole role,
    MemberStatus status
) {
}
```

## 사업자 검증 설계

점주 회원가입 시 member-service가 공공데이터포털 국세청 사업자등록 상태조회 API를 호출한다.
가게명은 사용자가 직접 입력하며 국세청 조회 조건으로 사용하지 않는다.

```java
public interface BusinessVerificationService {
    BusinessVerificationResult verify(String businessRegistrationNumber);
}
```

현재 구현:

- 하이픈을 제거한 숫자 10자리 사업자등록번호로 상태조회를 요청한다.
- 납세자상태 코드 `01`인 계속사업자만 가입을 허용하고 `VERIFIED`로 저장한다.
- 휴업자, 폐업자, 미등록 번호는 가입을 거절한다.
- 상태조회는 대표자 본인 여부나 사업장 소유권을 증명하지 않는다.
- 외부 API가 응답하지 않거나 올바르지 않은 응답을 반환하면 임의로 통과시키지 않고 503 오류를 반환한다.
- 검증 상태는 member-service가 계산하며 auth-service 요청에서 받지 않는다.

## 트랜잭션 및 일관성 경계

- member-service는 Member, 역할별 Profile, Location을 자기 DB 트랜잭션으로 저장한다.
- auth-service는 AuthAccount, LocalCredential 또는 OAuthConnection, RefreshToken을 자기 DB 트랜잭션으로 저장한다.
- auth-service의 가입 흐름 조정과 member-service 호출은 auth DB 트랜잭션 밖에서 수행한다.
- auth-service는 회원 생성 응답을 받은 뒤 `SignupPersistenceService`에서 auth 데이터 저장에 필요한 짧은 트랜잭션을 시작한다.
- member-service의 점주 가입은 국세청 상태조회 호출을 member DB 트랜잭션 밖에서 수행한 뒤 검증된 회원 데이터를 저장한다.
- 두 서비스 사이에는 분산 트랜잭션을 사용하지 않는다.
- auth-service가 member-service 회원 생성을 성공한 뒤 auth-service 저장에 실패할 수 있다.
- 초기에는 auth-service가 member-service의 내부 보상 삭제 API를 호출해 생성된 member/profile/location을 삭제한다.
- member-service의 보상 삭제는 통합 테스트로 `Member`, 역할별 Profile, Location, Worker child record 삭제를 검증한다.
- 추후 장애 격리와 재처리가 중요해지면 이벤트 기반 saga 또는 outbox 패턴을 검토한다.
