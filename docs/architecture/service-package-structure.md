# Service Package Structure

이 문서는 각 백엔드 서비스에서 사용할 서비스 내부 패키지 구조와 계층 규칙을 정의합니다.

## 기본 방향

- 각 백엔드 서비스는 같은 기본 구조를 사용한다.
- 서비스 간 데이터 참조는 외부 ID로 저장한다.
- 초기 서비스 간 통신은 REST HTTP 조회를 기본으로 한다.
- 이벤트 기반 데이터 복제는 조회 성능, 장애 격리, 비동기 상태 전파가 필요해질 때 도입한다.
- API 요청/응답 DTO와 JPA Entity는 분리한다.
- 비즈니스 로직은 Controller가 아니라 Service에 둔다.
- 상위 계층은 하위 계층을 참조할 수 있지만, 하위 계층은 상위 계층을 참조하지 않는다.
- 계층을 건너뛰는 직접 참조는 지양한다.

## 공통 패키지 구조

아래 구조에서 `{service}`는 각 서비스의 루트 패키지를 의미합니다.

예:

- `com.workernotfound.auth`
- `com.workernotfound.user`
- `com.workernotfound.job`

```text
com.workernotfound.{service}
├── {Service}Application.java
├── global
│   ├── config
│   ├── exception
│   ├── response
│   └── security
├── domain
│   └── {domain}
│       ├── controller
│       ├── service
│       ├── repository
│       ├── entity
│       └── dto
│           ├── request
│           └── response
└── external
    ├── client
    ├── event
    └── redis
```

## Auth Service 예시

```text
com.workernotfound.auth
├── AuthServiceApplication.java
├── global
│   ├── config
│   ├── exception
│   ├── response
│   └── security
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
│   │   ├── service
│   │   └── dto
│   │       ├── request
│   │       └── response
│   └── token
│       ├── service
│       ├── repository
│       ├── entity
│       └── dto
│           ├── request
│           └── response
└── external
    ├── client
    ├── event
    └── redis
```

## 패키지 책임

### global

서비스 전체에서 공통으로 사용하는 설정과 기반 코드를 둡니다.

- `config`: Spring 설정
- `exception`: 공통 예외, 에러 코드, 예외 핸들러
- `response`: 공통 API 응답 형식
- `security`: Spring Security, JWT 필터, 인증 관련 공통 설정

### domain

서비스 내부의 주요 도메인별 코드를 둡니다.

- `controller`: HTTP 요청과 응답 처리
- `service`: 유스케이스와 트랜잭션 처리
- `repository`: 영속성 접근
- `entity`: JPA Entity
- `dto/request`: API 요청 DTO
- `dto/response`: API 응답 DTO

### external

서비스 외부 시스템과 연결되는 코드를 둡니다.

- `client`: 다른 서비스 REST HTTP client
- `event`: 이벤트 발행 또는 구독 코드
- `redis`: Redis 저장소, 캐시, 토큰 저장 연동

## 계층 참조 규칙

계층 흐름은 다음과 같습니다.

```text
Controller -> Service -> Repository -> Entity
```

- 상위 계층은 하위 계층을 참조할 수 있다.
- 하위 계층은 상위 계층을 참조하지 않는다.
- Controller는 Repository 또는 Entity를 직접 참조하지 않는다.
- Controller는 request DTO를 받는다.
- ApplicationService는 request DTO를 직접 받을 수 있다.
- CommandService와 FindService에는 request/response DTO를 넘기지 않는다.
- CommandService와 FindService에는 필요하면 command/query 객체 또는 primitive/domain 값을 넘긴다.
- Entity는 Controller, Service, DTO에 의존하지 않는다.

## Service CQRS 구조

Service 계층은 명령과 조회를 분리하는 CQRS 스타일을 사용합니다.

```text
domain/{domain}/service
├── {Domain}CommandService.java
├── {Domain}FindService.java
└── {Domain}ApplicationService.java
```

- `CommandService`: 생성, 수정, 삭제, 상태 변경을 담당한다.
- `FindService`: 단순 조회와 조회 보조 로직을 담당한다.
- `Application Service`: 여러 하위 서비스를 조합해 하나의 유스케이스를 처리한다.

예:

```text
domain/account/service
├── AccountCommandService.java
├── AccountFindService.java
└── AccountApplicationService.java
```

단, 도메인이 작을 때는 Service를 과도하게 나누지 않고 하나로 시작한 뒤, 책임이 커질 때 분리할 수 있습니다.

## DTO 규칙

- Request DTO와 Response DTO는 패키지를 분리한다.
- DTO는 API 경계에서만 사용한다.
- JPA Entity를 API 응답으로 직접 노출하지 않는다.
- DTO는 기본적으로 Java `record`를 사용한다.
- class 또는 static nested class는 `record` 사용이 적합하지 않은 경우에만 사용한다.
- Service 계층에서는 DTO builder나 응답 조립 코드가 드러나지 않도록 한다.
- DTO 변환은 DTO 정적 팩토리 메서드 또는 별도 converter에서 처리한다.

선택 기준:

- `record`: 불변 객체 표현이 간단하고 request/response DTO에 적합하다.
- `class`: Bean Validation, 기본 생성자, Jackson 설정, 문서화 도구와의 호환성 이슈가 있을 때 선택할 수 있다.
- `static nested class`: 하나의 API 묶음 안에 요청/응답 DTO를 모을 수 있지만, 파일이 커질 수 있다.

변환 방식 기준:

- DTO 정적 팩토리 메서드: `SignupResponse.from(result)`처럼 호출부가 간단하고, 단순 변환에 적합하다.
- 별도 converter: 변환 로직이 복잡하거나 여러 DTO에서 재사용될 때 적합하다.
- Service 내부 builder 조립: Service의 유스케이스 흐름을 흐리기 쉬우므로 지양한다.

기본 변환 방향:

- 단순 변환은 DTO의 정적 팩토리 메서드를 사용한다.
- 복잡하거나 재사용되는 변환은 별도 converter를 사용한다.
- 처음부터 모든 변환에 converter를 만들지는 않는다.

## 서비스 간 사용자 정보 조회 예시

초기에는 외부 ID를 저장하고 필요한 시점에 HTTP로 조회합니다.

```text
job-service
└── job
    └── entity
        └── Job.employerUserId
```

공고 상세 조회 시:

```text
Client -> job-service -> user-service
                 <- 사용자 요약 정보
       <- 공고 + 사용자 요약 정보
```

나중에 조회 빈도나 장애 격리 요구가 커지면 이벤트 기반 복제를 도입할 수 있습니다.

```text
user-service -> UserProfileUpdated event -> job-service
job-service stores user summary snapshot
```

## 적용 기준

- 모든 서비스는 이 구조를 기본으로 따른다.
- 도메인이 작거나 아직 기능이 단순한 경우에는 불필요한 빈 패키지나 클래스를 미리 만들지 않는다.
- 외부 HTTP client, event, redis 패키지는 실제 연동이 생길 때 추가할 수 있다.
- 이 문서의 규칙과 다른 구조가 필요하면 이유를 설명하고 결정 문서에 기록한다.
