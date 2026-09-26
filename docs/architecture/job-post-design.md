# 공고 등록·조회와 지원 접수 승인

## 범위

`job-service`는 공고와 업종 카테고리의 원본을 소유한다. 현재는 점주의 공고 등록, 공고 상세·목록 조회, `matching-service`가 지원 저장 전에 호출하는 지원 접수 승인 내부 API를 제공한다. 공고 상태 전이, 모집 자리 예약·확정·반환, 모집 완료 알림, 결제 예치 후 공개는 후속 작업이다.

## 공개 API

응답은 공통 `ApiResponse`를 따른다. 인증이 필요한 요청은 auth-service가 발급한 Bearer JWT를 사용한다.

| 명령 | 경로 | 인증 | 요청/응답 |
| --- | --- | --- | --- |
| 공고 등록 | `POST /api/jobs` | `OWNER` 역할 | 사업장·카테고리·근무 일시·급여·모집 인원·위경도·긴급도·지원 마감 / `data` 공고 ID |
| 상세 조회 | `GET /api/jobs/{id}` | 없음 | 가게·카테고리 이름·주소·급여·근무 일시와 시간·설명·모집 인원 |
| 목록 조회 | `GET /api/jobs/search` | 없음 | 위치·거리·급여·시작 시각·카테고리·유형·페이지 조건 / 공고 카드 목록과 페이지 정보 |

공고 등록 규칙:

- 점주 회원 ID는 요청 본문이 아니라 JWT의 `memberId`를 사용한다.
- 종료 시각은 시작 시각 이후여야 한다. 자정을 넘으면 `isEndTimeNextDay=true`로 보낸다.
- 지원 마감은 현재 이후이고 근무 시작 이전이어야 한다.
- 시급은 10,320원 이상, 모집 인원은 1명 이상이다. 카테고리는 저장된 ID여야 한다.
- 사업장 소유권 검증은 `BusinessValidator` 포트 뒤에 있다. 현재 구현체 `StubBusinessValidator`는 경고 로그만 남기고 통과시키므로 실제 소유권 검증으로 간주하지 않는다.
- 등록 즉시 `OPEN`으로 저장한다. 결제 예치 후 공개 정책은 아직 반영되지 않았다.

목록 조회 규칙:

- `OPEN`이고 지원 마감 전인 공고만 반환한다.
- `type=URGENT`이면 긴급도 `HIGH` 공고만 마감 임박 순으로 정렬한다. 그 외에는 거리 오름차순이며 거리를 계산할 수 없는 공고는 뒤에 둔다.
- `maxDistanceKm`을 사용하려면 `workerLat`, `workerLng`가 필요하다. `minWage`는 `maxWage`보다 클 수 없다.
- 기본 페이지는 0, 크기는 20, 최대 크기는 100이다.
- 후보 공고를 모두 조회한 뒤 애플리케이션 메모리에서 필터·정렬·페이지 처리한다. 공고 수가 늘어나면 DB 조건 조회와 공간 인덱스로 바꿔야 한다.

상세 조회의 `applicantCount`는 원본이 `matching-service`에 있고 조회 계약이 없으므로 현재 `null`이다. 상세 조회는 공고 상태와 관계없이 반환한다.

## 내부 API

`/api/jobs/internal/**`는 `X-Internal-Secret`이 일치해야 하며, 불일치하면 401 `GLOBAL-401-001`이다. 보안 설정의 `permitAll`은 이 필터가 먼저 검증한 뒤 적용된다.

| 명령 | 경로 | 요청/응답 |
| --- | --- | --- |
| 지원 접수 승인 | `POST /api/jobs/internal/{jobPostId}/application-admissions` | `workerMemberId` / `admissionId`, `jobPostId`, `jobVersion`, `ownerMemberId`, `categoryId`, `workDate`, `startTime`, `endTime`, `latitude`, `longitude`, `admittedAt`, `expiresAt` |

`Idempotency-Key`는 1~100자의 공백 없는 ASCII다. 공고 행을 비관적 잠금으로 잡은 뒤 같은 키의 승인을 조회한다. 따라서 같은 키의 동시 요청도 앞선 요청의 결과를 본다.

- 같은 키와 같은 공고·회원 요청이면 기존 승인을 반환한다. 만료됐거나 `RESERVED`가 아니면 `JOB-409-003`이다.
- 같은 키를 다른 공고나 회원 요청에 재사용하면 `JOB-409-004`다.
- 새 승인은 공고가 `OPEN`이고 지원 마감 전일 때만 발급한다. 승인 생성 시각이 지원 접수 시점이다.
- 승인은 모집 자리를 차감하지 않는다. 모집 인원 동시성은 매칭 확정 단계의 자리 예약에서 다룬다.
- 만료 시간은 `APPLICATION_ADMISSION_TTL`이며 기본 5분이다.

계약의 세부 배경은 [지원 도메인 설계](./matching-application-design.md)의 공고 담당 범위 계약을 따른다.

## 상태와 버전

공고 상태는 `OPEN`, `MATCHING`, `CLOSED`가 정의되어 있다. 현재는 생성 시 `OPEN`만 사용하며 상태 전이 API와 `job_status_histories` 기록은 아직 없다. 전체 전이 규칙은 [MVP 도메인 흐름](./mvp-domain-flow.md)을 따른다.

`job_posts.version`은 JPA 낙관적 잠금 값이며 1부터 시작한다. 지원 승인에는 발급 당시 버전을 `jobVersion`으로 저장한다. 이후 모집 완료 알림과 재오픈을 구분하는 기준으로 사용한다.

지원 승인 상태는 `RESERVED`, `CONSUMED`, `EXPIRED`다. `ApplicationSubmitted` 수신에 따른 `CONSUMED` 처리와 만료 상태 정리는 아직 구현되지 않았다.

## 영속성

- `industry_categories`: 업종 카테고리. 대분류와 하위 분류를 V4 마이그레이션에서 초기 데이터로 넣는다.
- `job_posts`: 사업장·점주 외부 ID, 카테고리 FK, 근무 일시, 급여, 모집 인원, 위경도, 긴급도, 지원 마감, 상태, 버전.
- `job_status_histories`: 공고 상태 전이 이력. 테이블과 엔티티만 있고 아직 기록하지 않는다.
- `job_application_admissions`: 공고 FK, 알바생 회원 외부 ID, 멱등 키(유일), 공고 버전, 승인 상태, 승인·만료·사용 시각.

사업장·점주·알바생 ID는 다른 서비스의 원본이므로 물리 FK 없이 외부 ID로만 저장한다.

## 오류 코드

| HTTP | 코드 | 의미 |
| --- | --- | --- |
| 400 | `JOB-400-001` | 근무 종료 시각이 올바르지 않음 |
| 400 | `JOB-400-002` | 지원 마감 시각이 올바르지 않음 |
| 400 | `JOB-400-003` | 검색 조건이 올바르지 않음 |
| 404 | `JOB-404-001` | 존재하지 않는 공고 |
| 404 | `JOB-404-002` | 존재하지 않는 카테고리 |
| 409 | `JOB-409-001` | 지원을 받지 않는 공고 |
| 409 | `JOB-409-002` | 지원 마감 시간이 지남 |
| 409 | `JOB-409-003` | 지원 접수 승인 만료 |
| 409 | `JOB-409-004` | 멱등 키를 다른 요청에 재사용 |

요청 형식·검증 오류와 인증·권한 오류는 공통 `GLOBAL-*` 코드를 사용한다. 예상하지 못한 오류는 원문을 노출하지 않는 500으로 응답한다. 공통 기준은 [오류 처리 검토](./error-handling-review.md)를 따른다.

## 후속 작업

- 매칭 확정용 모집 자리 예약·확정·반환 API와 모집 완료 알림 ([지원 도메인 설계](./matching-application-design.md))
- `PAYMENT_PENDING` 비공개 생성, 결제 주문 생성, 예치 상태 수신 후 공개 ([토스 예치 설계](./toss-deposit-design.md))
- 공고 상태 전이와 상태 이력 기록
- 지원 승인 `CONSUMED` 처리
- member-service 연동을 통한 사업장 소유권 검증
- DB 기반 검색 조건과 페이지 처리

## 실행과 검증

`.env.example`의 `JOB_*` 값을 개인 `.env`에 설정하고 공통 `AUTH_JWT_SECRET`, `INTERNAL_API_SECRET`을 맞춘다. `./scripts/local-run.sh infra`, `./scripts/local-run.sh job`으로 실행한다. HTTP 8083, 로컬 MySQL 3309를 사용한다. Swagger UI는 `/swagger-ui.html`이다. 통합 테스트는 MySQL Testcontainers로 지원 승인 발급·멱등·오류 코드, 내부 인증, JWT 오류 경계, Swagger 접근을 확인한다.
