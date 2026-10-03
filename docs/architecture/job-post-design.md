# 공고 등록·조회와 지원 접수 승인

## 범위

`job-service`는 공고와 업종 카테고리의 원본을 소유한다. 현재는 점주의 공고 등록, 공고 상세·목록 조회, `matching-service`가 지원 저장 전에 호출하는 지원 접수 승인 내부 API, 매칭 확정 Saga가 호출하는 모집 자리 예약·확정·반환 내부 API와 만료 예약 회수, 1인 예정 급여 계산을 제공한다. 공고 상태 전이와 상태 이력, 모집 완료 알림(`recruitment-completion` 호출 명령 저장과 재시도), 결제 예치 후 공개는 후속 작업이다. 자리 예약·확정만으로 모집 완료 연동이 끝난 것은 아니다.

## 공개 API

응답은 공통 `ApiResponse`를 따른다. 인증이 필요한 요청은 auth-service가 발급한 Bearer JWT를 사용한다.

| 명령 | 경로 | 인증 | 요청/응답 |
| --- | --- | --- | --- |
| 공고 등록 | `POST /api/jobs` | `OWNER` 역할 | 사업장·카테고리·근무 일시·급여·모집 인원·위경도·긴급도·지원 마감 / `data` 공고 ID |
| 상세 조회 | `GET /api/jobs/{id}` | 없음 | 가게·카테고리 이름·주소·급여·근무 일시와 시간·설명·모집 인원 |
| 목록 조회 | `GET /api/jobs/search` | 없음 | 위치·거리·급여·시작 시각·카테고리·유형·페이지 조건 / 공고 카드 목록과 페이지 정보 |

공고 등록 규칙:

- 점주 회원 ID는 요청 본문이 아니라 JWT의 `memberId`를 사용한다.
- 근무 시간은 분 단위로 1분 이상 24시간 이하여야 한다. 자정을 넘으면 `isEndTimeNextDay=true`로 보내며 이때 종료 시각은 시작 시각 이하다. 초 단위가 있는 시각, 익일 플래그 없이 종료가 시작 이전인 구간, 익일 플래그로 24시간을 넘는 구간은 `JOB-400-001`이다.
- 1인 예정 급여와 전체 예치 예정액(아래 [예정 급여 계산](#예정-급여-계산))을 계산할 수 없거나 허용 범위를 넘으면 `JOB-400-004`다.
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
| 모집 자리 예약 | `POST /api/jobs/internal/{jobPostId}/matching-seat-reservations` | `matchingId`, `applicationId`, `workerMemberId` / `reservationId`(문자열), `jobPostId`, `jobVersion`, `ownerMemberId`, `workDate`, `startTime`, `endTime`, `endTimeNextDay`, `lockedAmount`, `currency`, `reservedAt`, `expiresAt` |
| 모집 자리 확정 | `POST /api/jobs/internal/{jobPostId}/matching-seat-reservations/{reservationId}/confirm` | 본문 없음 / `reservationId`, `status` |
| 모집 자리 반환 | `POST /api/jobs/internal/{jobPostId}/matching-seat-reservations/{reservationId}/release` | 본문 없음 / `reservationId`, `status` |

`Idempotency-Key`는 1~100자의 공백 없는 ASCII다. 공고 행을 비관적 잠금으로 잡은 뒤 같은 키의 승인을 조회한다. 따라서 같은 공고에 같은 키로 온 동시 요청은 앞선 요청의 결과를 본다. 공고가 서로 다르면 잠그는 행도 달라 조회가 직렬화되지 않으므로, `uk_job_application_admissions_idempotency_key` 유일 제약이 마지막 방어선이다.

- 같은 키와 같은 공고·회원 요청이면 기존 승인을 반환한다. 만료됐거나 `RESERVED`가 아니면 `JOB-409-003`이다.
- 같은 키를 다른 공고나 회원 요청에 재사용하면 `JOB-409-004`다. 동시 요청이 유일 제약에서 걸린 경우에도 같은 코드로 응답한다.
- 제약 위반 변환은 저장 트랜잭션이 롤백된 뒤 전역 HTTP 예외 처리 경계인 `GlobalExceptionHandler`에서 한다. 서비스는 저장 예외를 변환하지 않고 전파한다. cause 사슬 전체에서 Hibernate `ConstraintViolationException`을 찾아 제약 이름이 정확히 일치할 때만 `JOB-409-004` 응답으로 변환한다. 중간에 다른 제약 위반을 만나도 탐색을 계속하고, 순환 참조에서는 종료한다. 대상 제약이 없는 무결성 위반은 서버 오류로 남긴다. MySQL이 반환하는 `테이블명.제약명`에서는 테이블명 접두사를 제거하고 비교한다.
- 새 승인은 공고가 `OPEN`이고 지원 마감 전일 때만 발급한다. 승인 생성 시각이 지원 접수 시점이다.
- 승인은 모집 자리를 차감하지 않는다. 모집 인원 동시성은 매칭 확정 단계의 자리 예약에서 다룬다.
- 만료 시간은 `APPLICATION_ADMISSION_TTL`이며 기본 5분이다.
- 응답의 점주 회원 ID, 업종 ID, 근무 일시, 위경도는 발급 당시 잠근 공고에서 승인 레코드에 함께 저장한 스냅샷이다. 응답을 만들 때 공고를 다시 읽지 않으므로 같은 멱등 키의 재요청은 공고가 바뀌어도 같은 응답을 받는다.

계약의 세부 배경은 [지원 도메인 설계](./matching-application-design.md)의 공고 담당 범위 계약을 따른다.

## 모집 자리 예약

매칭 확정 Saga의 첫 단계에서 남은 모집 인원 한 자리를 임시로 확보하고, 마지막 단계에서 확정 인원으로 반영한다. 후속 단계가 명확히 실패하면 Saga 보상으로 반환된다. 상태는 `RESERVED`, `CONSUMED`, `RELEASED`, `EXPIRED`다. 모든 명령은 `X-Internal-Secret`과 명령별 `Idempotency-Key`(1~100자의 공백 없는 ASCII)를 사용한다.

### 잠금과 불변식

예약·확정·반환·만료 회수는 모두 공고 행 → 예약 행 순서로 비관적 잠금을 잡는다. 같은 공고의 명령은 공고 행 잠금으로 직렬화되어 `CONSUMED 수 + 유효한 RESERVED 수 <= recruitCount`가 항상 유지된다. 현재 시각은 잠금을 얻은 뒤 `Clock` 빈에서 읽어 `expiresAt`과 비교하며, `expiresAt <= now`인 `RESERVED`는 유효하지 않다.

만료 예약 회수는 범위 조건 `UPDATE`를 쓰지 않는다. 보조 인덱스 범위 갱신은 gap 잠금을 잡아 다른 공고의 예약 `INSERT`와 교착될 수 있으므로, 공고 행 잠금 아래에서 대상 ID를 비잠금 조회한 뒤 기본 키로만 `EXPIRED`로 바꾼다.

### 예약

- 같은 키의 기존 예약이 있으면 `jobPostId + matchingId + applicationId + workerMemberId`가 모두 같을 때 저장된 스냅샷으로 원래 응답을 돌려준다. 공고를 다시 읽지 않고, `RELEASED`·`EXPIRED`여도 되살리거나 새로 발급하지 않는다. 하나라도 다르면 `JOB-409-004`다. 동시 최초 요청은 같은 공고 잠금 아래에서 키를 다시 조회하므로 하나만 생성된다. 서로 다른 공고에 같은 키를 쓴 동시 요청은 유일 제약에서 거절되며 `JOB-409-004`로 응답한다.
- 공고 상태는 `OPEN` 또는 `MATCHING`이어야 한다([MVP 도메인 흐름](./mvp-domain-flow.md)의 `MATCHING`은 지원 접수를 멈추고 매칭을 진행하는 상태다). 그 외 상태는 `JOB-409-005`다.
- 자리 예약 기한은 지원 마감이 아니라 근무 시작 시각(`workDate + startTime`)이다. 지원 마감 이후에도 기존 지원자의 매칭 확정은 가능하며, 근무 시작 이후에는 `JOB-409-006`이다.
- 같은 잠금 안에서 이 공고의 만료된 `RESERVED`를 먼저 `EXPIRED`로 회수한다. 스케줄러가 늦어도 만료 자리가 새 예약을 막지 않는다.
- 같은 공고에서 같은 `matchingId` 또는 `applicationId`가 `RESERVED`·`CONSUMED`로 자리를 점유하고 있으면 다른 키라도 `JOB-409-008`이다. 종료된(`RELEASED`·`EXPIRED`) 시도 이후에는 새 키로 다시 예약할 수 있다. `matchingId`에 영구 유일 제약을 두지 않는다.
- 점유 수가 `recruitCount` 이상이면 `JOB-409-007`이다.
- 응답 스냅샷은 발급 당시 공고의 버전, 점주 ID, 근무 일시, `endTimeNextDay`, 1인 예정 급여(`lockedAmount`, 정수 KRW), 통화 `KRW`다. 스냅샷과 요청 식별 필드는 `updatable = false`다.
- `expiresAt = reservedAt + MATCHING_SEAT_RESERVATION_TTL`(기본 10분, 지원 접수 승인 TTL과 별도 설정)이며 재요청으로 연장하지 않는다.

### 확정

- 예약이 경로의 공고에 속하지 않거나 없으면 `JOB-404-003`이다.
- 같은 확정 키가 다른 예약의 확정에 이미 쓰였으면 `JOB-409-004`다.
- 유효한 `RESERVED`는 `CONSUMED`로 바꾸고 확정 키와 시각을 기록한다.
- 이미 `CONSUMED`인 예약에 처음 확정한 키로 다시 요청하면 성공한다. 확정 응답이 유실된 경우의 복구이므로 원래 `expiresAt`이 지났어도 거절하지 않는다. 다른 키는 저장된 키를 덮어쓰지 않고 `JOB-409-010`이다.
- 기한이 지난 `RESERVED`와 `EXPIRED`는 `JOB-409-009`, `RELEASED`는 `JOB-409-010`이다.

### 반환

- 예약 존재와 키 재사용 검증은 확정과 같다.
- `RESERVED`는 `RELEASED`로 바꾼다. 이미 기한이 지났다면 `EXPIRED`로 회수한다.
- `EXPIRED`는 자리가 이미 회수됐으므로 성공한다. 처음 들어온 반환 키만 기록한다.
- 이미 `RELEASED`·반환 키가 기록된 `EXPIRED`는 처음 반환한 키일 때만 성공하고, 다른 키는 `JOB-409-010`이다.
- `CONSUMED`는 반환하지 않고 `JOB-409-010`이다.
- 반환은 경로의 예약 ID만 바꾸므로 오래된 시도의 늦은 반환이 같은 매칭의 새 예약에 영향을 주지 않는다.

### 만료 회수

`MATCHING_SEAT_RESERVATION_EXPIRY_SWEEP_INTERVAL`(기본 1분)마다 만료된 `RESERVED`가 있는 공고를 최대 `MATCHING_SEAT_RESERVATION_EXPIRY_SWEEP_BATCH_SIZE`(기본 100)개 고른다. 공고마다 별도 트랜잭션에서 공고 행을 잠그고 회수하므로 한 트랜잭션이 여러 공고를 오래 잠그지 않는다. 상태 조건이 있어 반복 실행이나 여러 인스턴스 실행에서도 중복 회수하지 않는다. 한 공고의 실패는 로그만 남기고 다음 공고를 계속 처리한다. `MATCHING_SEAT_RESERVATION_EXPIRY_SWEEP_ENABLED=false`로 끌 수 있으며, 꺼져 있어도 새 예약 요청이 해당 공고의 만료 예약을 회수한다.

### 멱등 키 제약 변환

예약·확정·반환 키는 각각 `uk_job_matching_seat_reservations_idempotency_key`, `uk_job_matching_seat_reservations_confirm_key`, `uk_job_matching_seat_reservations_release_key` 유일 제약을 갖는다. 지원 접수 승인과 같은 방식으로 `GlobalExceptionHandler`가 롤백 이후 이 제약 이름과 정확히 일치할 때만 `JOB-409-004`로 변환한다.

## 예정 급여 계산

`JobWageCalculator`가 공고 도메인의 단일 계산 기준이다. payment-service에 같은 공식을 복제하지 않는다.

초기 계산 기준:

- 근무 분 = 종료 분 - 시작 분 + (`endTimeNextDay`이면 1,440). 1분 이상 1,440분 이하이며 시작·종료 시각은 분 단위여야 한다.
- `extraWage`는 시간당 추가 시급(원/시간)이다. `null`이면 0으로 본다.
- 1인 예정 급여 = ⌊근무 분 × (`baseHourlyWage` + `extraWage`) ÷ 60⌋. 시급을 먼저 합산하고 정수 연산으로 한 번만 원 미만을 버린다. 부동소수점을 쓰지 않는다.
- 전체 예치 예정액 = 1인 예정 급여 × `recruitCount`. 내림한 1인 금액에 곱하므로 자리 예약의 `lockedAmount` 합계와 항상 같다.
- 1인 금액은 1원 이상이어야 하고, 전체 금액은 payment-service 금액 컬럼(DECIMAL(19,2))의 정수부 한도 이하이며 `long` 곱셈 오버플로를 허용하지 않는다.
- 휴게시간은 공고 입력값이 없어 차감하지 않는다.

미결정 정책: 휴게시간 입력과 차감, 시간대(야간·휴일) 가산 기준과 공식, 긴급도 가산, 통화별 최소 단위. 결정되면 새 계산 기준으로 바꾸고 기존 예약 스냅샷 금액은 유지한다.

## 상태와 버전

공고 상태는 `OPEN`, `MATCHING`, `CLOSED`가 정의되어 있다. 현재는 생성 시 `OPEN`만 사용하며 상태 전이 API와 `job_status_histories` 기록은 아직 없다. 전체 전이 규칙은 [MVP 도메인 흐름](./mvp-domain-flow.md)을 따른다.

`job_posts.version`은 JPA 낙관적 잠금 값이며 1부터 시작한다. 지원 승인에는 발급 당시 버전을 `jobVersion`으로 저장한다. 이후 모집 완료 알림과 재오픈을 구분하는 기준으로 사용한다.

지원 승인 상태는 `RESERVED`, `CONSUMED`, `EXPIRED`다. `ApplicationSubmitted` 수신에 따른 `CONSUMED` 처리와 만료 상태 정리는 아직 구현되지 않았다.

자리 예약에는 발급 당시 공고 버전을 `jobVersion`으로 저장한다. 자리 예약·확정은 공고 상태를 바꾸지 않는다. 모집 인원이 모두 확정됐을 때의 상태 전이와 이력 기록은 후속 작업이다.

## 영속성

- `industry_categories`: 업종 카테고리. 대분류와 하위 분류를 V4 마이그레이션에서 초기 데이터로 넣는다.
- `job_posts`: 사업장·점주 외부 ID, 카테고리 FK, 근무 일시, 급여, 모집 인원, 위경도, 긴급도, 지원 마감, 상태, 버전.
- `job_status_histories`: 공고 상태 전이 이력. 테이블과 엔티티만 있고 아직 기록하지 않는다.
- `job_application_admissions`: 공고 FK, 알바생 회원 외부 ID, 멱등 키(유일), 공고 버전, 승인 상태, 승인·만료·사용 시각, 발급 당시 공고 스냅샷(점주 회원 ID, 업종 ID, 근무 일시, 위도·경도). 스냅샷 컬럼은 `updatable = false`로 두어 발급 후 바뀌지 않는다.
- `job_matching_seat_reservations`(V8): 공고 FK, 매칭·지원·알바생 외부 ID, 예약·확정·반환 멱등 키(각각 유일), 공고 버전, 상태, 예약·확정·반환·만료 처리 시각과 발급 시 확정한 `expires_at`, 발급 당시 스냅샷(점주 ID, 근무 일시, `end_time_next_day`, `locked_amount` 정수 KRW, `currency`). 인덱스는 공고별 점유 집계·중복 점유 확인용 `(job_post_id, status, expires_at)`과 만료 대상 공고 조회용 `(status, expires_at)`이다.

사업장·점주·알바생 ID는 다른 서비스의 원본이므로 물리 FK 없이 외부 ID로만 저장한다.

## 오류 코드

| HTTP | 코드 | 의미 |
| --- | --- | --- |
| 400 | `JOB-400-001` | 근무 종료 시각이 올바르지 않음 |
| 400 | `JOB-400-002` | 지원 마감 시각이 올바르지 않음 |
| 400 | `JOB-400-003` | 검색 조건이 올바르지 않음 |
| 400 | `JOB-400-004` | 급여 계산 금액이 허용 범위를 벗어남 |
| 404 | `JOB-404-001` | 존재하지 않는 공고 |
| 404 | `JOB-404-002` | 존재하지 않는 카테고리 |
| 404 | `JOB-404-003` | 공고에 속한 모집 자리 예약이 없음 |
| 409 | `JOB-409-001` | 지원을 받지 않는 공고 |
| 409 | `JOB-409-002` | 지원 마감 시간이 지남 |
| 409 | `JOB-409-003` | 지원 접수 승인 만료 |
| 409 | `JOB-409-004` | 멱등 키를 다른 요청에 재사용 |
| 409 | `JOB-409-005` | 매칭할 수 없는 공고 상태 |
| 409 | `JOB-409-006` | 근무 시작 시각이 지나 자리 예약 불가 |
| 409 | `JOB-409-007` | 남은 모집 자리가 없음 |
| 409 | `JOB-409-008` | 같은 매칭 또는 지원이 이미 자리를 점유 |
| 409 | `JOB-409-009` | 모집 자리 예약 만료 |
| 409 | `JOB-409-010` | 자리 예약 상태와 맞지 않는 요청(반환된 예약 확정, 확정된 예약 반환, 다른 키로 이미 처리됨) |

요청 형식·검증 오류와 인증·권한 오류는 공통 `GLOBAL-*` 코드를 사용한다. 예상하지 못한 오류는 원문을 노출하지 않는 500으로 응답한다. 공통 기준은 [오류 처리 검토](./error-handling-review.md)를 따른다.

## 후속 작업

- 모집 완료 판단에 따른 공고 상태 전이와 `job_status_histories` 기록, `recruitment-completion` 호출 명령 저장과 재시도 실행기 ([지원 도메인 설계](./matching-application-design.md))
- `PAYMENT_PENDING` 비공개 생성, 결제 주문 생성(전체 예치 예정액은 `JobWageCalculator` 사용), 예치 상태 수신 후 공개 ([토스 예치 설계](./toss-deposit-design.md))
- 공고 상태 전이와 상태 이력 기록
- 지원 승인 `CONSUMED` 처리
- member-service 연동을 통한 사업장 소유권 검증
- DB 기반 검색 조건과 페이지 처리

## 실행과 검증

`.env.example`의 `JOB_*` 값을 개인 `.env`에 설정하고 공통 `AUTH_JWT_SECRET`, `INTERNAL_API_SECRET`을 맞춘다. `./scripts/local-run.sh infra`, `./scripts/local-run.sh job`으로 실행한다. HTTP 8083, 로컬 MySQL 3309를 사용한다. Swagger UI는 `/swagger-ui.html`이다. 통합 테스트는 MySQL Testcontainers로 지원 승인 발급·멱등·오류 코드, 모집 자리 예약·확정·반환의 멱등·동시성·만료 경계·제약 변환, 만료 회수, 급여 계산, 내부 인증, JWT 오류 경계, Swagger 접근을 확인한다. 시간 경계 테스트는 테스트용 `MutableClock` 빈으로 현재 시각을 고정한다.
